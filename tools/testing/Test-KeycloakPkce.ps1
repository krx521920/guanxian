[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string] $Authority,
    [Parameter(Mandatory)] [string] $ClientId,
    [Parameter(Mandatory)] [string] $Username,
    [Parameter(Mandatory)] [string] $Password,
    [Parameter(Mandatory)] [string] $RedirectUri,
    [switch] $AllowInsecureLoopback
)

$ErrorActionPreference = 'Stop'

function ConvertTo-Base64Url([byte[]] $Bytes) {
    [Convert]::ToBase64String($Bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

$authorityValue = $Authority.TrimEnd('/')
$discovery = Invoke-RestMethod "$authorityValue/.well-known/openid-configuration"
if ($discovery.issuer -ne $authorityValue) {
    throw "Discovery issuer mismatch: expected $authorityValue, received $($discovery.issuer)"
}
if ($discovery.code_challenge_methods_supported -notcontains 'S256') {
    throw 'The identity provider does not advertise PKCE S256 support.'
}

$random = [byte[]]::new(48)
[Security.Cryptography.RandomNumberGenerator]::Fill($random)
$verifier = ConvertTo-Base64Url $random
$challenge = ConvertTo-Base64Url (
    [Security.Cryptography.SHA256]::HashData([Text.Encoding]::ASCII.GetBytes($verifier)))
$state = [Guid]::NewGuid().ToString('N')
$nonce = [Guid]::NewGuid().ToString('N')
$query = @{
    client_id = $ClientId
    redirect_uri = $RedirectUri
    response_type = 'code'
    scope = 'openid profile email'
    state = $state
    nonce = $nonce
    code_challenge = $challenge
    code_challenge_method = 'S256'
}
$encodedQuery = ($query.GetEnumerator() | ForEach-Object {
        "$([Uri]::EscapeDataString($_.Key))=$([Uri]::EscapeDataString([string]$_.Value))"
    }) -join '&'

$session = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
$loginPage = Invoke-WebRequest "$($discovery.authorization_endpoint)?$encodedQuery" `
    -WebSession $session -SkipHttpErrorCheck
if ($loginPage.StatusCode -ne 200) {
    throw "Keycloak login page returned HTTP $($loginPage.StatusCode)."
}
$form = [regex]::Match(
    $loginPage.Content,
    '<form[^>]*id="kc-form-login"[^>]*>',
    [Text.RegularExpressions.RegexOptions]::IgnoreCase)
if (-not $form.Success) {
    throw 'Keycloak login form was not found.'
}
$actionMatch = [regex]::Match(
    $form.Value,
    'action="([^"]+)"',
    [Text.RegularExpressions.RegexOptions]::IgnoreCase)
if (-not $actionMatch.Success) {
    throw 'Keycloak login action was not found.'
}
$action = [Net.WebUtility]::HtmlDecode($actionMatch.Groups[1].Value)
$loginHeaders = @{}
$actionUri = [Uri] $action
$authorizationUri = [Uri] $discovery.authorization_endpoint
if ($actionUri.Scheme -ne $authorizationUri.Scheme -or
    $actionUri.Host -ne $authorizationUri.Host -or
    $actionUri.Port -ne $authorizationUri.Port) {
    throw 'The Keycloak login form action is not same-origin with the authorization endpoint.'
}
$secureCookies = @($session.Cookies.GetAllCookies() | Where-Object Secure)
if ($actionUri.Scheme -eq 'http' -and $secureCookies.Count -gt 0) {
    $address = $null
    $isIpAddress = [Net.IPAddress]::TryParse($actionUri.Host, [ref] $address)
    if (-not $AllowInsecureLoopback -or -not $isIpAddress -or
        -not [Net.IPAddress]::IsLoopback($address)) {
        throw 'The IdP issued Secure cookies over HTTP; use HTTPS. The loopback override is test-only.'
    }
    $loginHeaders.Cookie = ($session.Cookies.GetAllCookies() | ForEach-Object {
        "$($_.Name)=$($_.Value)"
    }) -join '; '
}

$handler = [Net.Http.HttpClientHandler]::new()
$handler.AllowAutoRedirect = $false
$client = [Net.Http.HttpClient]::new($handler)
$request = [Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::Post, $actionUri)
if ($loginHeaders.ContainsKey('Cookie')) {
    [void] $request.Headers.TryAddWithoutValidation('Cookie', $loginHeaders.Cookie)
}
$formValues = [Collections.Generic.List[Collections.Generic.KeyValuePair[string,string]]]::new()
$formValues.Add([Collections.Generic.KeyValuePair[string,string]]::new('username', $Username))
$formValues.Add([Collections.Generic.KeyValuePair[string,string]]::new('password', $Password))
$formValues.Add([Collections.Generic.KeyValuePair[string,string]]::new('credentialId', ''))
$formValues.Add([Collections.Generic.KeyValuePair[string,string]]::new('login', 'Sign In'))
$request.Content = [Net.Http.FormUrlEncodedContent]::new($formValues)
$response = $client.SendAsync($request).GetAwaiter().GetResult()
$loginResponse = [pscustomobject]@{
    StatusCode = [int] $response.StatusCode
    Headers = [pscustomobject]@{ Location = $response.Headers.Location }
    Content = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
}
$request.Dispose()
$client.Dispose()
$handler.Dispose()
$location = [string] $loginResponse.Headers.Location
if ([string]::IsNullOrWhiteSpace($location)) {
    $cookieSummary = ($session.Cookies.GetAllCookies() | ForEach-Object {
        "$($_.Name)@$($_.Domain)$($_.Path);secure=$($_.Secure)"
    }) -join ', '
    $feedbackMatch = [regex]::Match(
        [string] $loginResponse.Content,
        '<[^>]*class="[^"]*kc-feedback-text[^"]*"[^>]*>(.*?)</[^>]+>',
        [Text.RegularExpressions.RegexOptions]::IgnoreCase -bor
            [Text.RegularExpressions.RegexOptions]::Singleline)
    if ($feedbackMatch.Success) {
        $feedback = [Net.WebUtility]::HtmlDecode(
            [regex]::Replace($feedbackMatch.Groups[1].Value, '<[^>]+>', ' '))
        $feedback = [regex]::Replace($feedback, '\s+', ' ').Trim()
        throw "Keycloak callback redirect is missing; HTTP $($loginResponse.StatusCode); action=$action; cookies=$cookieSummary`: $feedback"
    }
    $plainText = [Net.WebUtility]::HtmlDecode(
        [regex]::Replace([string] $loginResponse.Content, '<[^>]+>', ' '))
    $plainText = [regex]::Replace($plainText, '\s+', ' ').Trim()
    $summary = $plainText.Substring(0, [Math]::Min(1200, $plainText.Length))
    throw "Keycloak callback redirect is missing; HTTP $($loginResponse.StatusCode); action=$action; cookies=$cookieSummary`: $summary"
}

$callback = [Uri] $location
if ($callback.GetLeftPart([UriPartial]::Path) -ne $RedirectUri) {
    throw "Unexpected callback URI: $location"
}
$parameters = [Web.HttpUtility]::ParseQueryString($callback.Query)
if ($parameters['state'] -ne $state) {
    throw 'OIDC state validation failed.'
}
$code = $parameters['code']
if ([string]::IsNullOrWhiteSpace($code)) {
    throw "Authorization code is missing: $location"
}

$tokens = Invoke-RestMethod $discovery.token_endpoint -Method Post `
    -ContentType 'application/x-www-form-urlencoded' `
    -Body @{
        grant_type = 'authorization_code'
        client_id = $ClientId
        redirect_uri = $RedirectUri
        code = $code
        code_verifier = $verifier
    }
if ([string]::IsNullOrWhiteSpace($tokens.access_token) -or
    [string]::IsNullOrWhiteSpace($tokens.id_token)) {
    throw 'The token response is incomplete.'
}
$userInfo = Invoke-RestMethod $discovery.userinfo_endpoint `
    -Headers @{ Authorization="Bearer $($tokens.access_token)" }
if ($userInfo.preferred_username -ne $Username) {
    throw 'UserInfo does not match the login identity.'
}

[pscustomobject]@{
    Issuer = $discovery.issuer
    AuthorizationPage = $loginPage.StatusCode
    LoginRedirect = [int] $loginResponse.StatusCode
    StateValidated = $true
    PkceMethod = 'S256'
    AuthorizationCodeIssued = $true
    TokenExchange = 200
    AccessTokenIssued = $true
    IdTokenIssued = $true
    UserInfoUsername = $userInfo.preferred_username
}
