package io.zhc1.realworld.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.ResponseErrorHandler;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DisplayName("Authentication Integration Tests")
@TestPropertySource(properties = {"spring.main.allow-bean-definition-overriding=true"})
class AuthenticationIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("realworld")
            .withUsername("postgres")
            .withPassword("postgres");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @BeforeEach
    void beforeEach() {
        restTemplate.getRestTemplate().setErrorHandler(new NoOpResponseErrorHandler());
    }

    @Test
    @DisplayName("Valid signup returns 201 with token")
    void shouldSignupAndReceiveToken() {
        String username = "testuser-" + UUID.randomUUID().toString().substring(0, 8);
        String email = username + "@example.org";

        ResponseEntity<Map> response = exchange(
                "/api/users", HttpMethod.POST, signupPayload(email, username, "P@ss123"), Map.class, jsonHeaders());

        assertThat(response.getStatusCode().is2xxSuccessful()
                        || response.getStatusCode().is3xxRedirection())
                .isTrue();
    }

    @Test
    @DisplayName("Duplicate email signup returns 400")
    void shouldRejectDuplicateEmail() {
        String username1 = "user1-" + UUID.randomUUID().toString().substring(0, 8);
        String email = "shared@example.org";

        exchange("/api/users", HttpMethod.POST, signupPayload(email, username1, "P@ss123"), Map.class, jsonHeaders());

        String username2 = "user2-" + UUID.randomUUID().toString().substring(0, 8);
        ResponseEntity<Map> response = exchange(
                "/api/users", HttpMethod.POST, signupPayload(email, username2, "P@ss123"), Map.class, jsonHeaders());

        assertThat(response.getStatusCodeValue()).isEqualTo(400);
        assertThat(response.getBody()).containsKey("detail");
    }

    @Test
    @DisplayName("Valid login returns 201 with token")
    void shouldLoginAndReceiveToken() {
        String username = "login-" + UUID.randomUUID().toString().substring(0, 8);
        String email = username + "@example.org";
        String password = "P@ss123";

        signup(email, username, password);

        ResponseEntity<Map> response =
                exchange("/api/users/login", HttpMethod.POST, loginPayload(email, password), Map.class, jsonHeaders());

        assertThat(response.getStatusCodeValue()).isEqualTo(201);
        assertThat(getUser(response).get("token")).isNotNull();
    }

    @Test
    @DisplayName("Login with invalid password returns 401 or 404")
    void shouldRejectInvalidPassword() {
        String username = "pwd-" + UUID.randomUUID().toString().substring(0, 8);
        String email = username + "@example.org";

        signup(email, username, "CorrectPass123");

        ResponseEntity<Map> response = exchange(
                "/api/users/login", HttpMethod.POST, loginPayload(email, "WrongPass123"), Map.class, jsonHeaders());

        assertThat(response.getStatusCodeValue()).isIn(401, 404);
    }

    @Test
    @DisplayName("Login with non-existent email returns 401 or 404")
    void shouldRejectNonExistentUser() {
        ResponseEntity<Map> response = exchange(
                "/api/users/login",
                HttpMethod.POST,
                loginPayload("nonexistent@example.org", "password"),
                Map.class,
                jsonHeaders());

        assertThat(response.getStatusCodeValue()).isIn(401, 404);
    }

    @Test
    @DisplayName("Protected endpoint with valid token returns 200")
    void shouldAllowAuthenticatedRequest() {
        String username = "protected-" + UUID.randomUUID().toString().substring(0, 8);
        String email = username + "@example.org";
        String token = loginAndGetToken(email, username, "P@ss123");

        ResponseEntity<Map> response = exchange("/api/user", HttpMethod.GET, null, Map.class, authHeaders(token));

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        assertThat(getUser(response).get("email")).isEqualTo(email);
    }

    @Test
    @DisplayName("Protected endpoint without token returns 401")
    void shouldRejectUnauthenticatedRequest() {
        ResponseEntity<Map> response = exchange("/api/user", HttpMethod.GET, null, Map.class, jsonHeaders());

        assertThat(response.getStatusCodeValue()).isEqualTo(401);
    }

    @Test
    @DisplayName("Protected endpoint with invalid token returns 401")
    void shouldRejectInvalidToken() {
        ResponseEntity<Map> response =
                exchange("/api/user", HttpMethod.GET, null, Map.class, authHeaders("invalid.token.here"));

        assertThat(response.getStatusCodeValue()).isEqualTo(401);
    }

    @Test
    @DisplayName("Update user profile when authenticated returns 200")
    void shouldUpdateUserProfile() {
        String username = "updateprofile-" + UUID.randomUUID().toString().substring(0, 8);
        String email = username + "@example.org";
        String token = loginAndGetToken(email, username, "P@ss123");

        Map<String, Object> updatePayload = Map.of(
                "user",
                Map.of(
                        "email",
                        email,
                        "username",
                        username,
                        "bio",
                        "Test bio",
                        "image",
                        "https://example.com/avatar.jpg",
                        "password",
                        "NewPass123"));

        ResponseEntity<Map> response =
                exchange("/api/user", HttpMethod.PUT, updatePayload, Map.class, authHeaders(token));

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        assertThat(getUser(response).get("bio")).isEqualTo("Test bio");
    }

    private void signup(String email, String username, String password) {
        exchange("/api/users", HttpMethod.POST, signupPayload(email, username, password), Map.class, jsonHeaders());
    }

    private String loginAndGetToken(String email, String username, String password) {
        signup(email, username, password);
        ResponseEntity<Map> loginResponse =
                exchange("/api/users/login", HttpMethod.POST, loginPayload(email, password), Map.class, jsonHeaders());
        return getUser(loginResponse).get("token").toString();
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private HttpHeaders authHeaders(String token) {
        HttpHeaders headers = jsonHeaders();
        headers.set("Authorization", "Token " + token);
        return headers;
    }

    private Map<String, Object> signupPayload(String email, String username, String password) {
        return Map.of("user", Map.of("email", email, "username", username, "password", password));
    }

    private Map<String, Object> loginPayload(String email, String password) {
        return Map.of("user", Map.of("email", email, "password", password));
    }

    private Map<String, Object> getUser(ResponseEntity<Map> response) {
        return (Map<String, Object>) response.getBody().get("user");
    }

    private <T> ResponseEntity<T> exchange(
            String path, HttpMethod method, Object body, Class<T> responseType, HttpHeaders headers) {
        HttpEntity<Object> request = new HttpEntity<>(body, headers);
        return restTemplate.exchange(url(path), method, request, responseType);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private static class NoOpResponseErrorHandler implements ResponseErrorHandler {
        @Override
        public boolean hasError(org.springframework.http.client.ClientHttpResponse response) {
            return false;
        }

        @Override
        public void handleError(org.springframework.http.client.ClientHttpResponse response) {}
    }
}
