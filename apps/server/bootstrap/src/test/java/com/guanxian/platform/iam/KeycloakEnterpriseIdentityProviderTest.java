package com.guanxian.platform.iam;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class KeycloakEnterpriseIdentityProviderTest {
    @TempDir Path directory;
    HttpServer server;ObjectMapper mapper=new ObjectMapper();KeycloakEnterpriseIdentityProvider provider;
    Map<String,Object> user;List<String> requests=new ArrayList<>();List<JsonNode> writes=new ArrayList<>();
    UUID operation=UUID.randomUUID();String id=UUID.randomUUID().toString();String secret="fixture-management-secret";
    boolean privileged,redirect,oversized;
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            try {
                String path=exchange.getRequestURI().getPath(),method=exchange.getRequestMethod();requests.add(method+" "+path);
                String payload=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
                Object response=Map.of();int status=200;
                if(path.endsWith("/token")) {
                    assertTrue(payload.contains("grant_type=client_credentials"));assertTrue(payload.contains(secret));
                    if(redirect) { status=302;exchange.getResponseHeaders().set("Location","/credential-leak"); }
                    else response=Map.of("access_token","fixture-admin-token", "padding", oversized?"x".repeat(262145):"");
                } else {
                    assertEquals("Bearer fixture-admin-token",exchange.getRequestHeaders().getFirst("Authorization"));
                    if(!payload.isEmpty()) writes.add(mapper.readTree(payload));
                    if(path.endsWith("/users") && method.equals("POST")) {
                        JsonNode data=mapper.readTree(payload);assertFalse(data.has("credentials"));assertFalse(data.path("enabled").asBoolean());
                        user=mapper.convertValue(data,Map.class);user.put("id",id);status=201;
                    } else if(path.endsWith("/users")) {
                        assertTrue(exchange.getRequestURI().getRawQuery().contains("exact=true"));response=user==null?List.of():List.of(user);
                    } else if(path.endsWith("/composite")) response=privileged?List.of(Map.of("name","SYSTEM_ADMIN")):List.of(Map.of("name","offline_access"));
                    else if(path.endsWith("/"+id) && method.equals("GET")) response=user;
                    else status=204;
                }
                byte[] bytes=mapper.writeValueAsBytes(response);
                exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.sendResponseHeaders(status,status==204?-1:bytes.length);
                if(status!=204)exchange.getResponseBody().write(bytes);
            } finally { exchange.close(); }
        });server.start();
        Path file=directory.resolve("secret");Files.writeString(file,secret);
        provider=new KeycloakEnterpriseIdentityProvider(true,"http://127.0.0.1:"+server.getAddress().getPort()+"/identity/realms/test",
                "account-provisioner",file.toString(),mapper,new MockEnvironment().withProperty("spring.profiles.active","test"));
    }
    @AfterEach void close() { server.stop(0); }
    @Test void followsActualAdminContractAndUsesOnlyTemporaryPasswordWrites() {
        assertEquals(id,provider.ensureDisabledUser("company.owner",operation));
        assertEquals(id,provider.ensureDisabledUser("company.owner",operation));
        assertEquals(1,requests.stream().filter(p->p.equals("POST /identity/admin/realms/test/users")).count());
        provider.verifyUser(id,"company.owner",operation);provider.logout(id);provider.temporaryPassword(id,"Fixture-Only!Password2026");provider.setEnabled(id,true);
        assertTrue(writes.stream().anyMatch(n->n.path("temporary").asBoolean() && n.path("type").asText().equals("password")));
        assertFalse(requests.stream().anyMatch(p->p.contains("/credentials")));
        assertFalse(requests.stream().anyMatch(p->p.startsWith("POST")&&p.contains("role-mappings")));
    }
    @Test void refusesExistingUsernameWithoutOperationProofAndPrivilegedIdentity() {
        provider.ensureDisabledUser("company.owner",operation);
        assertThrows(RuntimeException.class,()->provider.ensureDisabledUser("company.owner",UUID.randomUUID()));
        privileged=true;
        assertThrows(RuntimeException.class,()->provider.verifyUser(id,"company.owner",operation));
        assertFalse(requests.stream().anyMatch(p->p.contains("/reset-password")));
    }
    @Test void neverFollowsCredentialRedirectOrDisclosesSecretInErrors() {
        redirect=true;
        var error=assertThrows(RuntimeException.class,()->provider.ensureDisabledUser("company.owner",operation));
        assertFalse(error.toString().contains(secret));assertFalse(requests.stream().anyMatch(p->p.contains("credential-leak")));
    }
    @Test void productionRejectsHttpAndMalformedRealmConfiguration() {
        MockEnvironment env=new MockEnvironment();env.setActiveProfiles("production");
        assertThrows(IllegalStateException.class,()->new KeycloakEnterpriseIdentityProvider(true,"http://example.test/realms/test","client","secret",mapper,env));
        assertThrows(IllegalStateException.class,()->new KeycloakEnterpriseIdentityProvider(true,"https://example.test/realms/test?query=1","client","secret",mapper,env));
    }
    @Test void rejectsOversizedUpstreamBodyWithoutProceedingToIdentityWrites() {
        oversized=true;
        assertThrows(RuntimeException.class,()->provider.ensureDisabledUser("company.owner",operation));
        assertEquals(1,requests.size());assertTrue(writes.isEmpty());
    }
}
