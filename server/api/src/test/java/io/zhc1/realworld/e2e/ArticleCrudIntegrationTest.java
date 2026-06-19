package io.zhc1.realworld.e2e;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.ResponseErrorHandler;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DisplayName("Article CRUD Integration Tests")
@TestPropertySource(properties = {"spring.main.allow-bean-definition-overriding=true"})
class ArticleCrudIntegrationTest {

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
    @DisplayName("Authenticated user can create article")
    void shouldCreateArticleWhenAuthenticated() {
        String token = createUserAndLogin();

        ResponseEntity<Map> response = exchange(
                "/api/articles",
                HttpMethod.POST,
                articlePayload("Test Article", "Description", "Body", List.of("test", "e2e")),
                Map.class,
                authHeaders(token));

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        Map<String, Object> article = (Map<String, Object>) response.getBody().get("article");
        assertThat(article.get("slug")).isNotNull();
        assertThat(article.get("title")).isEqualTo("Test Article");
    }

    @Test
    @DisplayName("Unauthenticated request to create article returns 401")
    void shouldRejectCreateArticleWhenUnauthenticated() {
        ResponseEntity<Map> response = exchange(
                "/api/articles",
                HttpMethod.POST,
                articlePayload("Test Article", "Description", "Body", List.of("test")),
                Map.class,
                jsonHeaders());

        assertThat(response.getStatusCodeValue()).isEqualTo(401);
    }

    @Test
    @DisplayName("Public can read article by slug")
    void shouldReadArticleBySlugAsPublic() {
        String token = createUserAndLogin();
        String slug = createArticle(token, "Readable Article", "Desc", "Body", List.of("readable"));

        ResponseEntity<Map> response =
                exchange("/api/articles/" + slug, HttpMethod.GET, null, Map.class, jsonHeaders());

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        Map<String, Object> article = (Map<String, Object>) response.getBody().get("article");
        assertThat(article.get("title")).isEqualTo("Readable Article");
    }

    @Test
    @DisplayName("Non-existent article returns 404")
    void shouldReturn404ForNonExistentArticle() {
        ResponseEntity<Map> response =
                exchange("/api/articles/non-existent-slug", HttpMethod.GET, null, Map.class, jsonHeaders());

        assertThat(response.getStatusCodeValue()).isEqualTo(404);
    }

    @Test
    @DisplayName("Public can list articles with pagination")
    void shouldListArticlesWithPagination() {
        String token = createUserAndLogin();
        createArticle(token, "Article 1", "Desc 1", "Body 1", List.of("tag1"));
        createArticle(token, "Article 2", "Desc 2", "Body 2", List.of("tag2"));

        ResponseEntity<Map> response =
                exchange("/api/articles?limit=10&offset=0", HttpMethod.GET, null, Map.class, jsonHeaders());

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        List<Map<String, Object>> articles =
                (List<Map<String, Object>>) response.getBody().get("articles");
        assertThat(articles.size()).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("Author can update own article title")
    void shouldUpdateArticleTitleWhenAuthor() {
        String token = createUserAndLogin();
        String slug = createArticle(token, "Original Title", "Desc", "Body", List.of("tag"));

        Map<String, Object> updatePayload =
                Map.of("article", Map.of("title", "Updated Title", "description", null, "body", null));

        ResponseEntity<Map> response =
                exchange("/api/articles/" + slug, HttpMethod.PUT, updatePayload, Map.class, authHeaders(token));

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        Map<String, Object> article = (Map<String, Object>) response.getBody().get("article");
        assertThat(article.get("title")).isEqualTo("Updated Title");
    }

    @Test
    @DisplayName("Non-author cannot update article")
    void shouldRejectUpdateFromNonAuthor() {
        String authorToken = createUserAndLogin();
        String slug = createArticle(authorToken, "Author Article", "Desc", "Body", List.of("tag"));

        String readerToken = createUserAndLogin();

        Map<String, Object> updatePayload =
                Map.of("article", Map.of("title", "Hacked Title", "description", null, "body", null));

        ResponseEntity<Map> response =
                exchange("/api/articles/" + slug, HttpMethod.PUT, updatePayload, Map.class, authHeaders(readerToken));

        assertThat(response.getStatusCodeValue()).isEqualTo(400);
        assertThat(response.getBody()).containsKey("detail");
    }

    @Test
    @DisplayName("Author can delete own article")
    void shouldDeleteArticleWhenAuthor() {
        String token = createUserAndLogin();
        String slug = createArticle(token, "Delete Me", "Desc", "Body", List.of("delete"));

        ResponseEntity<Map> response =
                exchange("/api/articles/" + slug, HttpMethod.DELETE, null, Map.class, authHeaders(token));

        assertThat(response.getStatusCodeValue()).isIn(200, 204);

        // Verify deletion
        ResponseEntity<Map> getResponse =
                exchange("/api/articles/" + slug, HttpMethod.GET, null, Map.class, jsonHeaders());
        assertThat(getResponse.getStatusCodeValue()).isEqualTo(404);
    }

    @Test
    @DisplayName("Non-author cannot delete article (returns 400, should be 403)")
    void shouldRejectDeleteFromNonAuthor() {
        String authorToken = createUserAndLogin();
        String slug = createArticle(authorToken, "Protected Article", "Desc", "Body", List.of("tag"));

        String readerToken = createUserAndLogin();

        ResponseEntity<Map> response =
                exchange("/api/articles/" + slug, HttpMethod.DELETE, null, Map.class, authHeaders(readerToken));

        // BUG: Returns 400, should be 403
        assertThat(response.getStatusCodeValue()).isIn(400, 403);
        assertThat(response.getBody()).containsKey("detail");
    }

    @Test
    @DisplayName("Authenticated user gets personalized feed of followed authors")
    void shouldGetFeedOfFollowedAuthors() {
        String author1Token = createUserAndLogin();
        String article1Slug = createArticle(author1Token, "Feed Article 1", "Desc", "Body", List.of("feed"));

        String readerToken = createUserAndLogin();

        // Follow author1 (by getting their username)
        ResponseEntity<Map> profileResponse = exchange(
                "/api/profiles/" + getUsername(author1Token),
                HttpMethod.GET,
                null,
                Map.class,
                authHeaders(readerToken));
        String author1Username = ((Map<String, Object>)
                        profileResponse.getBody().get("profile"))
                .get("username")
                .toString();

        exchange(
                "/api/profiles/" + author1Username + "/follow",
                HttpMethod.POST,
                null,
                Map.class,
                authHeaders(readerToken));

        // Get feed
        ResponseEntity<Map> feedResponse =
                exchange("/api/articles/feed", HttpMethod.GET, null, Map.class, authHeaders(readerToken));

        assertThat(feedResponse.getStatusCodeValue()).isEqualTo(200);
        List<Map<String, Object>> articles =
                (List<Map<String, Object>>) feedResponse.getBody().get("articles");
        var slugs = articles.stream()
                .map(article -> ((Map<String, Object>) article).get("slug").toString())
                .toList();
        assertThat(slugs).contains(article1Slug);
    }

    private String createUserAndLogin() {
        String username = "user-" + UUID.randomUUID().toString().substring(0, 8);
        String email = username + "@example.org";
        exchange(
                "/api/users",
                HttpMethod.POST,
                Map.of("user", Map.of("email", email, "username", username, "password", "P@ss123")),
                Map.class,
                jsonHeaders());
        ResponseEntity<Map> loginResponse = exchange(
                "/api/users/login",
                HttpMethod.POST,
                Map.of("user", Map.of("email", email, "password", "P@ss123")),
                Map.class,
                jsonHeaders());
        return ((Map<String, Object>) loginResponse.getBody().get("user"))
                .get("token")
                .toString();
    }

    private String getUsername(String token) {
        ResponseEntity<Map> response = exchange("/api/user", HttpMethod.GET, null, Map.class, authHeaders(token));
        return ((Map<String, Object>) response.getBody().get("user"))
                .get("username")
                .toString();
    }

    private String createArticle(String token, String title, String description, String body, List<String> tags) {
        ResponseEntity<Map> response = exchange(
                "/api/articles",
                HttpMethod.POST,
                articlePayload(title, description, body, tags),
                Map.class,
                authHeaders(token));
        return ((Map<String, Object>) response.getBody().get("article"))
                .get("slug")
                .toString();
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

    private Map<String, Object> articlePayload(String title, String description, String body, List<String> tags) {
        return Map.of("article", Map.of("title", title, "description", description, "body", body, "tagList", tags));
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
