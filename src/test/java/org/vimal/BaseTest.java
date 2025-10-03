package org.vimal;

import io.restassured.RestAssured;
import lombok.extern.slf4j.Slf4j;
import org.testng.annotations.AfterSuite;
import org.testng.annotations.BeforeSuite;
import org.vimal.dtos.RoleDto;
import org.vimal.dtos.UserDto;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

import static io.restassured.RestAssured.given;
import static org.vimal.api.AdminCallsUsingGlobalAdminUser.createRoles;
import static org.vimal.api.AdminCallsUsingGlobalAdminUser.createUsers;
import static org.vimal.api.AuthenticationCalls.getAccessToken;
import static org.vimal.api.AuthenticationCalls.logout;
import static org.vimal.constants.Common.MAX_BATCH_SIZE_OF_ROLE_CREATION_AT_A_TIME;
import static org.vimal.constants.Common.MAX_BATCH_SIZE_OF_USER_CREATION_AT_A_TIME;
import static org.vimal.helpers.CleanUpHelper.cleanUpTestRoles;
import static org.vimal.helpers.CleanUpHelper.cleanUpTestUsers;
import static org.vimal.helpers.DtosHelper.*;

@Slf4j
public abstract class BaseTest {
    protected static final Set<UserDto> TEST_USERS = ConcurrentHashMap.newKeySet();
    protected static final Set<RoleDto> TEST_ROLES = ConcurrentHashMap.newKeySet();
    private static final String BASE_URL = "http://localhost:8080";
    private static final String BASE_PATH = "api/v1";
    public static final String X_DEVICE_ID_HEADER = "X-Device-ID";
    public static final String DEFAULT_DEVICE_ID = "Test-Device-001";
    public static final String TEST_EMAIL = System.getenv("TEST_EMAIL");
    public static final String TEST_EMAIL_PASSWORD = System.getenv("TEST_EMAIL_PASSWORD");
    public static final String GLOBAL_ADMIN_USERNAME = System.getenv("GLOBAL_ADMIN_USERNAME");
    public static final String GLOBAL_ADMIN_PASSWORD = System.getenv("GLOBAL_ADMIN_PASSWORD");
    public static String GLOBAL_ADMIN_ACCESS_TOKEN;

    @BeforeSuite
    public void setUpBeforeSuite() throws ExecutionException, InterruptedException {
        log.info(
                "Setting RestAssured with base Url: '{}' & base path: '{}'",
                BASE_URL,
                BASE_PATH
        );
        RestAssured.baseURI = BASE_URL;
        RestAssured.basePath = BASE_PATH;
        log.info("Enabling logging of request & response if validation fails.");
        RestAssured.requestSpecification = given().header(X_DEVICE_ID_HEADER, DEFAULT_DEVICE_ID);
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
        GLOBAL_ADMIN_ACCESS_TOKEN = getAccessToken(
                GLOBAL_ADMIN_USERNAME,
                GLOBAL_ADMIN_PASSWORD
        );
    }

    @AfterSuite
    public void cleanupAfterSuite() {
        log.info("Cleaning up environment after all tests.");
        if (!TEST_USERS.isEmpty()) {
            log.info("Deleting test users.");
            cleanUpTestUsers(TEST_USERS);
            TEST_USERS.clear();
        }
        if (!TEST_ROLES.isEmpty()) {
            log.info("Deleting test roles.");
            cleanUpTestRoles(TEST_ROLES);
            TEST_ROLES.clear();
        }
        try {
            logout(GLOBAL_ADMIN_ACCESS_TOKEN);
        } catch (Exception ignored) {
        }
        log.info("Cleanup completed.");
    }

    protected static UserDto createTestUser() throws ExecutionException, InterruptedException {
        return createTestUser(createRandomUserDto());
    }

    protected static UserDto createTestUserRandomValidEmail() throws ExecutionException, InterruptedException {
        return createTestUser(createRandomUserDtoWithRandomValidEmail());
    }

    protected static UserDto createTestUser(Set<String> roles) throws ExecutionException, InterruptedException {
        return createTestUser(createRandomUserDto(roles));
    }

    protected static UserDto createTestUser(UserDto user) throws ExecutionException, InterruptedException {
        createTestUsers(Set.of(user));
        return user;
    }

    protected static void createTestUsers(Set<UserDto> users) throws ExecutionException, InterruptedException {
        Iterator<UserDto> iterator = users.iterator();
        Set<UserDto> batch = new HashSet<>();
        UserDto user;
        while (iterator.hasNext()) {
            batch.clear();
            while (iterator.hasNext() &&
                    batch.size() < MAX_BATCH_SIZE_OF_USER_CREATION_AT_A_TIME) {
                user = iterator.next();
                TEST_USERS.add(user);
                batch.add(user);
            }
            createUsers(
                    batch,
                    null
            ).then()
                    .statusCode(200);
        }
    }

    protected static RoleDto createTestRole() throws ExecutionException, InterruptedException {
        return createTestRole(createRandomRoleDto());
    }

    protected static RoleDto createTestRole(Set<String> permissions) throws ExecutionException, InterruptedException {
        return createTestRole(createRandomRoleDto(permissions));
    }

    protected static RoleDto createTestRole(RoleDto role) throws ExecutionException, InterruptedException {
        createTestRoles(Set.of(role));
        return role;
    }

    protected static void createTestRoles(Set<RoleDto> roles) throws ExecutionException, InterruptedException {
        Iterator<RoleDto> iterator = roles.iterator();
        Set<RoleDto> batch = new HashSet<>();
        RoleDto role;
        while (iterator.hasNext()) {
            batch.clear();
            while (iterator.hasNext() &&
                    batch.size() < MAX_BATCH_SIZE_OF_ROLE_CREATION_AT_A_TIME) {
                role = iterator.next();
                TEST_ROLES.add(role);
                batch.add(role);
            }
            createRoles(
                    batch,
                    null
            ).then()
                    .statusCode(200);
        }
    }
}
