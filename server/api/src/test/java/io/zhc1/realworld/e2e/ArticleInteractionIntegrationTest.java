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
@DisplayName("Article Interaction Integration Tests")
@TestPropertySource(properties = {"spring.main.allow-bean-definition-overriding=true"})
class ArticleInteractionIntegrationTest {

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
    @DisplayName("Authenticated user can add comment to article")
    void shouldAddCommentToArticle() {
        String authorToken = createUserAndLogin();
        String slug = createArticle(authorToken, "Commentable Article", "Desc", "Body", List.of("comments"));

        String readerToken = createUserAndLogin();

        ResponseEntity<Map> response = exchange(
                "/api/articles/" + slug + "/comments",
                HttpMethod.POST,
                Map.of("comment", Map.of("body", "Great article!")),
                Map.class,
                authHeaders(readerToken));

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        Map<String, Object> comment = (Map<String, Object>) response.getBody().get("comment");
        assertThat(comment.get("body")).isEqualTo("Great article!");
    }

    @Test
    @DisplayName("Public can read comments on article")
    void shouldReadCommentsOnArticle() {
        String authorToken = createUserAndLogin();
        String slug = createArticle(authorToken, "Article with Comments", "Desc", "Body", List.of("tag"));

        String commenterToken = createUserAndLogin();
        exchange(
                "/api/articles/" + slug + "/comments",
                HttpMethod.POST,
                Map.of("comment", Map.of("body", "First comment")),
                Map.class,
                authHeaders(commenterToken));

        ResponseEntity<Map> response =
                exchange("/api/articles/" + slug + "/comments", HttpMethod.GET, null, Map.class, jsonHeaders());

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        List<Map<String, Object>> comments =
                (List<Map<String, Object>>) response.getBody().get("comments");
        var bodies = comments.stream()
                .map(c -> ((Map<String, Object>) c).get("body").toString())
                .toList();
        assertThat(bodies).contains("First comment");
    }

    @Test
    @DisplayName("Comment author can delete their comment")
    void shouldDeleteCommentAsAuthor() {
        String articleAuthorToken = createUserAndLogin();
        String slug = createArticle(articleAuthorToken, "Deletable Comments", "Desc", "Body", List.of("tag"));

        String commenterToken = createUserAndLogin();
        ResponseEntity<Map> commentResponse = exchange(
                "/api/articles/" + slug + "/comments",
                HttpMethod.POST,
                Map.of("comment", Map.of("body", "Delete me")),
                Map.class,
                authHeaders(commenterToken));
        int commentId =
                ((Number) ((Map<String, Object>) commentResponse.getBody().get("comment")).get("id")).intValue();

        ResponseEntity<Void> deleteResponse = exchange(
                "/api/articles/" + slug + "/comments/" + commentId,
                HttpMethod.DELETE,
                null,
                Void.class,
                authHeaders(commenterToken));

        assertThat(deleteResponse.getStatusCodeValue()).isIn(200, 204);
    }

    @Test
    @DisplayName("Non-comment author cannot delete comment")
    void shouldRejectDeleteCommentFromNonAuthor() {
        String articleAuthorToken = createUserAndLogin();
        String slug = createArticle(articleAuthorToken, "Protected Comments", "Desc", "Body", List.of("tag"));

        String commenterToken = createUserAndLogin();
        ResponseEntity<Map> commentResponse = exchange(
                "/api/articles/" + slug + "/comments",
                HttpMethod.POST,
                Map.of("comment", Map.of("body", "Protected comment")),
                Map.class,
                authHeaders(commenterToken));
        int commentId =
                ((Number) ((Map<String, Object>) commentResponse.getBody().get("comment")).get("id")).intValue();

        String otherReaderToken = createUserAndLogin();

        ResponseEntity<Map> deleteResponse = exchange(
                "/api/articles/" + slug + "/comments/" + commentId,
                HttpMethod.DELETE,
                null,
                Map.class,
                authHeaders(otherReaderToken));

        assertThat(deleteResponse.getStatusCodeValue()).isEqualTo(400);
    }

    @Test
    @DisplayName("Authenticated user can favorite article")
    void shouldFavoriteArticle() {
        String authorToken = createUserAndLogin();
        String slug = createArticle(authorToken, "Favoriteable Article", "Desc", "Body", List.of("fav"));

        String readerToken = createUserAndLogin();

        ResponseEntity<Map> response = exchange(
                "/api/articles/" + slug + "/favorite", HttpMethod.POST, null, Map.class, authHeaders(readerToken));

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        Map<String, Object> article = (Map<String, Object>) response.getBody().get("article");
        assertThat(article.get("favorited")).isEqualTo(true);
        assertThat(((Number) article.get("favoritesCount")).intValue()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("Authenticated user can unfavorite article")
    void shouldUnfavoriteArticle() {
        String authorToken = createUserAndLogin();
        String slug = createArticle(authorToken, "Unfavoriteable Article", "Desc", "Body", List.of("tag"));

        String readerToken = createUserAndLogin();

        // Favorite first
        exchange("/api/articles/" + slug + "/favorite", HttpMethod.POST, null, Map.class, authHeaders(readerToken));

        // Unfavorite
        ResponseEntity<Map> response = exchange(
                "/api/articles/" + slug + "/favorite", HttpMethod.DELETE, null, Map.class, authHeaders(readerToken));

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        Map<String, Object> article = (Map<String, Object>) response.getBody().get("article");
        assertThat(article.get("favorited")).isEqualTo(false);
    }

    @Test
    @DisplayName("Authenticated user can follow another user")
    void shouldFollowUser() {
        String userToFollowToken = createUserAndLogin();
        String userToFollowUsername = getUsername(userToFollowToken);

        String followerToken = createUserAndLogin();

        ResponseEntity<Map> response = exchange(
                "/api/profiles/" + userToFollowUsername + "/follow",
                HttpMethod.POST,
                null,
                Map.class,
                authHeaders(followerToken));

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        Map<String, Object> profile = (Map<String, Object>) response.getBody().get("profile");
        assertThat(profile.get("following")).isEqualTo(true);
    }

    @Test
    @DisplayName("Authenticated user can unfollow another user")
    void shouldUnfollowUser() {
        String userToFollowToken = createUserAndLogin();
        String userToFollowUsername = getUsername(userToFollowToken);

        String followerToken = createUserAndLogin();

        // Follow first
        exchange(
                "/api/profiles/" + userToFollowUsername + "/follow",
                HttpMethod.POST,
                null,
                Map.class,
                authHeaders(followerToken));

        // Unfollow
        ResponseEntity<Map> response = exchange(
                "/api/profiles/" + userToFollowUsername + "/follow",
                HttpMethod.DELETE,
                null,
                Map.class,
                authHeaders(followerToken));

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        Map<String, Object> profile = (Map<String, Object>) response.getBody().get("profile");
        assertThat(profile.get("following")).isEqualTo(false);
    }

    @Test
    @DisplayName("Get user profile shows follow status")
    void shouldShowFollowStatusInProfile() {
        String userToken = createUserAndLogin();
        String username = getUsername(userToken);

        String otherUserToken = createUserAndLogin();

        // Check profile before follow
        ResponseEntity<Map> beforeFollowResponse =
                exchange("/api/profiles/" + username, HttpMethod.GET, null, Map.class, authHeaders(otherUserToken));
        Map<String, Object> profileBefore =
                (Map<String, Object>) beforeFollowResponse.getBody().get("profile");
        assertThat(profileBefore.get("following")).isEqualTo(false);

        // Follow
        exchange(
                "/api/profiles/" + username + "/follow", HttpMethod.POST, null, Map.class, authHeaders(otherUserToken));

        // Check profile after follow
        ResponseEntity<Map> afterFollowResponse =
                exchange("/api/profiles/" + username, HttpMethod.GET, null, Map.class, authHeaders(otherUserToken));
        Map<String, Object> profileAfter =
                (Map<String, Object>) afterFollowResponse.getBody().get("profile");
        assertThat(profileAfter.get("following")).isEqualTo(true);
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
                Map.of("article", Map.of("title", title, "description", description, "body", body, "tagList", tags)),
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
