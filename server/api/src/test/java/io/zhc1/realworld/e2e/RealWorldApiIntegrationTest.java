package io.zhc1.realworld.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
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
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.ResponseErrorHandler;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DisplayName("RealWorld API end-to-end tests")
@TestPropertySource(properties = {"spring.main.allow-bean-definition-overriding=true"})
class RealWorldApiIntegrationTest {
    private static final String PASSWORD = "P@ssword123";

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
    @DisplayName("Signup, login, publish an article, comment, delete comment and delete article")
    void shouldCompleteCoreUserAndArticleFlow() {
        String username = randomUsername("user");
        String email = makeEmail(username);
        registerUser(email, username, PASSWORD);

        String token = login(email, PASSWORD);
        String slug = publishArticle(
                token, "Integration Test Article", "A description", "Body content.", List.of("integration", "e2e"));

        var article = getArticle(token, slug);
        assertThat(article.get("slug")).isEqualTo(slug);
        assertThat(article.get("title")).isEqualTo("Integration Test Article");

        int commentId = createComment(token, slug, "This is a verify comment");
        var comments = getComments(token, slug);
        var bodies = comments.stream()
                .map(comment -> ((Map<?, ?>) comment).get("body").toString())
                .toList();
        assertThat(bodies).contains("This is a verify comment");

        deleteComment(token, slug, commentId);
        deleteArticle(token, slug);

        ResponseEntity<Map> deletedArticle =
                exchange("/api/articles/" + slug, HttpMethod.GET, null, Map.class, jsonHeaders());
        assertThat(deletedArticle.getStatusCodeValue()).isEqualTo(404);
    }

    @Test
    @DisplayName("Favorite, follow and feed flow between two users")
    void shouldFollowFavoriteAndSeeArticleInFeed() {
        String authorUsername = randomUsername("author");
        String authorEmail = makeEmail(authorUsername);
        registerUser(authorEmail, authorUsername, PASSWORD);
        String authorToken = login(authorEmail, PASSWORD);

        String readerUsername = randomUsername("reader");
        String readerEmail = makeEmail(readerUsername);
        registerUser(readerEmail, readerUsername, PASSWORD);
        String readerToken = login(readerEmail, PASSWORD);

        String slug = publishArticle(
                authorToken, "Author Article", "Author description", "Author content.", List.of("follow", "favorite"));

        var profile = getProfile(readerToken, authorUsername);
        assertThat(profile.get("following")).isEqualTo(false);

        followUser(readerToken, authorUsername);
        profile = getProfile(readerToken, authorUsername);
        assertThat(profile.get("following")).isEqualTo(true);

        var favoritedArticle = favoriteArticle(readerToken, slug);
        assertThat(favoritedArticle.get("favorited")).isEqualTo(true);
        assertThat(((Number) favoritedArticle.get("favoritesCount")).intValue()).isGreaterThanOrEqualTo(1);

        var feed = getFeed(readerToken);
        var slugs = feed.stream()
                .map(article -> ((Map<?, ?>) article).get("slug").toString())
                .toList();
        assertThat(slugs).contains(slug);
    }

    @Test
    @DisplayName("Unauthorized article creation is rejected")
    void shouldRejectUnauthorizedArticleCreation() {
        ResponseEntity<Map> response = exchange(
                "/api/articles",
                HttpMethod.POST,
                articlePayload("Bad Article", "No auth", "Should fail", List.of("bad")),
                Map.class,
                jsonHeaders());

        assertThat(response.getStatusCodeValue()).isEqualTo(401);
    }

    @Test
    @DisplayName("Non-author cannot delete another user article")
    void shouldRejectDeleteFromNonAuthor() {
        String ownerUsername = randomUsername("owner");
        String ownerEmail = makeEmail(ownerUsername);
        registerUser(ownerEmail, ownerUsername, PASSWORD);
        String ownerToken = login(ownerEmail, PASSWORD);

        String otherUsername = randomUsername("other");
        String otherEmail = makeEmail(otherUsername);
        registerUser(otherEmail, otherUsername, PASSWORD);
        String otherToken = login(otherEmail, PASSWORD);

        String slug = publishArticle(ownerToken, "Owner Article", "Owner desc", "Owner body.", List.of("delete"));
        ResponseEntity<Map> response =
                exchange("/api/articles/" + slug, HttpMethod.DELETE, null, Map.class, authHeaders(otherToken));

        assertThat(response.getStatusCodeValue()).isEqualTo(400);
        assertThat(response.getBody()).containsKey("detail");
    }

    private String randomUsername(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String makeEmail(String username) {
        return username + "@example.org";
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

    private void registerUser(String email, String username, String password) {
        ResponseEntity<Map> response = exchange(
                "/api/users", HttpMethod.POST, signupPayload(email, username, password), Map.class, jsonHeaders());

        assertThat(response.getStatusCode().is2xxSuccessful()
                        || response.getStatusCode().is3xxRedirection())
                .isTrue();
    }

    private String login(String email, String password) {
        ResponseEntity<Map> response =
                exchange("/api/users/login", HttpMethod.POST, loginPayload(email, password), Map.class, jsonHeaders());

        assertThat(response.getStatusCodeValue()).isEqualTo(201);
        return getUser(response).get("token").toString();
    }

    private String publishArticle(String token, String title, String description, String body, List<String> tags) {
        ResponseEntity<Map> response = exchange(
                "/api/articles",
                HttpMethod.POST,
                articlePayload(title, description, body, tags),
                Map.class,
                authHeaders(token));

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        return ((Map<?, ?>) response.getBody().get("article")).get("slug").toString();
    }

    private Map<String, Object> getArticle(String token, String slug) {
        ResponseEntity<Map> response =
                exchange("/api/articles/" + slug, HttpMethod.GET, null, Map.class, authHeaders(token));

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        return (Map<String, Object>) response.getBody().get("article");
    }

    private int createComment(String token, String slug, String body) {
        ResponseEntity<Map> response = exchange(
                "/api/articles/" + slug + "/comments",
                HttpMethod.POST,
                commentPayload(body),
                Map.class,
                authHeaders(token));

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        return ((Number) ((Map<?, ?>) response.getBody().get("comment")).get("id")).intValue();
    }

    private List<Map<String, Object>> getComments(String token, String slug) {
        ResponseEntity<Map> response =
                exchange("/api/articles/" + slug + "/comments", HttpMethod.GET, null, Map.class, authHeaders(token));

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        return (List<Map<String, Object>>) response.getBody().get("comments");
    }

    private void deleteComment(String token, String slug, int commentId) {
        ResponseEntity<Map> response = exchange(
                "/api/articles/" + slug + "/comments/" + commentId,
                HttpMethod.DELETE,
                null,
                Map.class,
                authHeaders(token));

        assertThat(response.getStatusCodeValue()).isIn(200, 204);
    }

    private void deleteArticle(String token, String slug) {
        ResponseEntity<Map> response =
                exchange("/api/articles/" + slug, HttpMethod.DELETE, null, Map.class, authHeaders(token));

        assertThat(response.getStatusCodeValue()).isIn(200, 204);
    }

    private Map<String, Object> followUser(String token, String username) {
        ResponseEntity<Map> response =
                exchange("/api/profiles/" + username + "/follow", HttpMethod.POST, null, Map.class, authHeaders(token));

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        return (Map<String, Object>) response.getBody().get("profile");
    }

    private Map<String, Object> getProfile(String token, String username) {
        ResponseEntity<Map> response =
                exchange("/api/profiles/" + username, HttpMethod.GET, null, Map.class, authHeaders(token));

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        return (Map<String, Object>) response.getBody().get("profile");
    }

    private Map<String, Object> favoriteArticle(String token, String slug) {
        ResponseEntity<Map> response =
                exchange("/api/articles/" + slug + "/favorite", HttpMethod.POST, null, Map.class, authHeaders(token));

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        return (Map<String, Object>) response.getBody().get("article");
    }

    private List<Map<String, Object>> getFeed(String token) {
        ResponseEntity<Map> response =
                exchange("/api/articles/feed", HttpMethod.GET, null, Map.class, authHeaders(token));

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        return (List<Map<String, Object>>) response.getBody().get("articles");
    }

    private Map<String, Object> getUser(ResponseEntity<Map> response) {
        return (Map<String, Object>) response.getBody().get("user");
    }

    private Map<String, Object> signupPayload(String email, String username, String password) {
        return Map.of("user", Map.of("email", email, "username", username, "password", password));
    }

    private Map<String, Object> loginPayload(String email, String password) {
        return Map.of("user", Map.of("email", email, "password", password));
    }

    private Map<String, Object> articlePayload(String title, String description, String body, List<String> tags) {
        return Map.of(
                "article",
                Map.of(
                        "title", title,
                        "description", description,
                        "body", body,
                        "tagList", tags));
    }

    private Map<String, Object> commentPayload(String body) {
        return Map.of("comment", Map.of("body", body));
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
        public boolean hasError(ClientHttpResponse response) {
            return false;
        }

        @Override
        public void handleError(ClientHttpResponse response) throws IOException {}
    }
}
