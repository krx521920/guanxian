package com.guanxian.platform.iam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanxian.platform.shared.error.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

@Component
@ConditionalOnProperty(name="guanxian.security.mode",havingValue="jwt",matchIfMissing=true)
class KeycloakEnterpriseIdentityProvider implements EnterpriseIdentityProvider {
    static final String MARKER="guanxianProvisioningId";
    private final boolean enabled;
    private final String realmUrl, adminUrl, clientId, secretFile;
    private final ObjectMapper mapper;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    KeycloakEnterpriseIdentityProvider(
            @Value("${guanxian.accounts.enabled:false}") boolean enabled,
            @Value("${guanxian.accounts.realm-url:${guanxian.security.jwt.issuer-uri:}}") String realmUrl,
            @Value("${guanxian.accounts.client-id:guanxian-account-provisioner}") String clientId,
            @Value("${guanxian.accounts.client-secret-file:}") String secretFile,
            ObjectMapper mapper, Environment environment) {
        this.enabled=enabled; this.realmUrl=realmUrl.replaceAll("/+$","");
        this.clientId=clientId; this.secretFile=secretFile; this.mapper=mapper;
        this.adminUrl=this.realmUrl.replaceFirst("/realms/([^/]+)$","/admin/realms/$1");
        if(enabled) {
            URI uri=SecurityConfig.validatedEndpoint("account provisioning",this.realmUrl,environment.getActiveProfiles());
            if(uri.getQuery()!=null || !uri.getPath().matches(".*/realms/[A-Za-z0-9_-]+") || adminUrl.equals(this.realmUrl)
                    || clientId.isBlank() || secretFile.isBlank()) throw new IllegalStateException("Invalid enterprise account provisioning configuration");
        }
    }
    public boolean enabled() { return enabled; }

    public String ensureDisabledUser(String username, UUID operationId) {
        String token=token();
        JsonNode matches=json(send("GET",adminUrl+"/users?username="+encode(username)+"&exact=true&max=2",null,token,200));
        if(!matches.isArray() || matches.size()>1) throw failure();
        if(matches.size()==1) {
            JsonNode existing=matches.get(0);
            verifyIdentity(existing,username,operationId);
            // Recovery can only reuse the identity marked by this exact durable operation.
            return id(existing);
        }
        send("POST",adminUrl+"/users",Map.of("username",username,"firstName","企业","lastName","负责人",
                "enabled",false,"requiredActions",List.of("UPDATE_PASSWORD"),
                "attributes",Map.of(MARKER,List.of(operationId.toString()))),token,201);
        // Do not follow or trust a Location header from an external service.
        JsonNode created=json(send("GET",adminUrl+"/users?username="+encode(username)+"&exact=true&max=2",null,token,200));
        if(!created.isArray() || created.size()!=1) throw failure();
        verifyIdentity(created.get(0),username,operationId);
        return id(created.get(0));
    }
    public void verifyUser(String subject,String username,UUID operationId) {
        String token=token();
        JsonNode user=json(send("GET",userUrl(subject),null,token,200));
        verifyIdentity(user,username,operationId);
        if(!id(user).equals(subject)) throw failure();
        JsonNode roles=json(send("GET",userUrl(subject)+"/role-mappings/realm/composite",null,token,200));
        if(!roles.isArray()) throw failure();
        // Provisioned identities receive application grants, never administrator/enterprise realm roles.
        for(JsonNode role:roles) if(Set.of("SYSTEM_ADMIN","ASSOCIATION_ADMIN","ASSOCIATION_OPERATOR",
                "ENTERPRISE_ADMIN","ENTERPRISE_MEMBER","OBSERVER").contains(role.path("name").asText())) throw failure();
    }
    public void setEnabled(String subject,boolean value) { send("PUT",userUrl(subject),Map.of("enabled",value),token(),204); }
    public void temporaryPassword(String subject,String password) {
        send("PUT",userUrl(subject)+"/reset-password",Map.of("type","password","temporary",true,"value",password),token(),204);
    }
    public void logout(String subject) { send("POST",userUrl(subject)+"/logout",null,token(),204); }

    private void verifyIdentity(JsonNode value,String username,UUID operationId) {
        JsonNode marker=value.path("attributes").path(MARKER);
        if(!username.equals(value.path("username").asText()) || !marker.isArray() || marker.size()!=1
                || !operationId.toString().equals(marker.get(0).asText())) throw failure();
    }
    private String id(JsonNode value) {
        String id=value.path("id").asText();
        try { return UUID.fromString(id).toString(); } catch(IllegalArgumentException e) { throw failure(); }
    }
    private String userUrl(String subject) { return adminUrl+"/users/"+UUID.fromString(subject); }
    private String token() {
        if(!enabled) throw new ApiException("ACCOUNT_PROVISIONING_DISABLED","管理员开户尚未配置，请联系运维人员",HttpStatus.SERVICE_UNAVAILABLE);
        try {
            Path path=Path.of(secretFile);
            if(!Files.isRegularFile(path) || Files.size(path)>8192) throw failure();
            String secret=Files.readString(path).strip();
            if(secret.isEmpty()) throw failure();
            String body="grant_type=client_credentials&client_id="+encode(clientId)+"&client_secret="+encode(secret);
            HttpRequest request=HttpRequest.newBuilder(URI.create(realmUrl+"/protocol/openid-connect/token"))
                    .timeout(Duration.ofSeconds(10)).header("Content-Type","application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            JsonNode data=json(exchange(request,200));
            String token=data.path("access_token").asText();
            if(token.isBlank() || token.length()>32768) throw failure();
            return token;
        } catch(ApiException e) { throw e; } catch(Exception e) { throw failure(); }
    }
    private String send(String method,String url,Object body,String token,int expected) {
        try {
            HttpRequest request=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                    .header("Authorization","Bearer "+token).header("Content-Type","application/json")
                    .method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
            return exchange(request,expected);
        } catch(ApiException e) { throw e; } catch(Exception e) { throw failure(); }
    }
    private String exchange(HttpRequest request,int expected) {
        BoundedBody body=new BoundedBody();
        CompletableFuture<HttpResponse<byte[]>> pending=http.sendAsync(request, info->body);
        try {
            // Covers headers AND body: a stalled upstream must not hold database locks indefinitely.
            var response=pending.get(10,TimeUnit.SECONDS);
            if(response.statusCode()!=expected) throw failure();
            return new String(response.body(),StandardCharsets.UTF_8);
        } catch(InterruptedException e) { Thread.currentThread().interrupt();throw failure(); }
        catch(Exception e) { throw failure(); }
        finally { body.cancel(); pending.cancel(true); }
    }
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        private boolean cancelled;
        public CompletionStage<byte[]> getBody() { return result; }
        public synchronized void onSubscribe(Flow.Subscription value) {
            if(cancelled || subscription!=null) { value.cancel(); return; }
            subscription=value; value.request(1);
        }
        public synchronized void onNext(List<ByteBuffer> buffers) {
            if(cancelled) return;
            for(ByteBuffer buffer:buffers) {
                if(buffer.remaining()>262144-bytes.size()) {
                    result.completeExceptionally(failure()); cancel(); return;
                }
                byte[] chunk=new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        public synchronized void onError(Throwable error) { result.completeExceptionally(failure()); }
        public synchronized void onComplete() { if(!cancelled) result.complete(bytes.toByteArray()); }
        synchronized void cancel() { cancelled=true; if(subscription!=null) subscription.cancel(); }
    }
    private JsonNode json(String value) { try { return mapper.readTree(value); } catch(Exception e) { throw failure(); } }
    private static String encode(String value) { return URLEncoder.encode(value,StandardCharsets.UTF_8); }
    static ApiException failure() { return new ApiException("IDENTITY_PROVIDER_OPERATION_FAILED","认证系统未完成操作；请刷新账号状态，核查配置后重试。不会覆盖已有同名账号。",HttpStatus.BAD_GATEWAY); }
}
