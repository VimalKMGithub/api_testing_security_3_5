package org.vimal.tests;

import com.google.zxing.NotFoundException;
import io.restassured.response.Response;
import jakarta.mail.MessagingException;
import org.testng.ITestContext;
import org.testng.annotations.Test;
import org.vimal.BaseTest;
import org.vimal.dtos.UserDto;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import static org.hamcrest.Matchers.*;
import static org.vimal.api.AuthenticationCalls.*;
import static org.vimal.api.UserCalls.*;
import static org.vimal.constants.Common.AUTHENTICATOR_APP_MFA;
import static org.vimal.constants.Common.ENABLE;
import static org.vimal.helpers.DtosHelper.*;
import static org.vimal.helpers.InvalidInputsHelper.*;
import static org.vimal.helpers.ResponseValidatorHelper.validateResponseOfGetSelfDetails;
import static org.vimal.utils.MailReaderUtility.getToken;
import static org.vimal.utils.QrUtility.extractSecretFromByteArrayOfQrCode;
import static org.vimal.utils.TotpUtility.generateTotp;

public class UserServiceTests extends BaseTest {
    @Test
    public void test_Registration_Success() throws ExecutionException, InterruptedException {
        UserDto user = createRandomUserDto();
        TEST_USERS.add(user);
        register(user).then()
                .statusCode(200)
                .body("message", containsStringIgnoringCase("Registration successful"))
                .body("user.id", notNullValue())
                .body("user.username", equalTo(user.getUsername()))
                .body("user.email", equalTo(user.getEmail()))
                .body("user.firstName", equalTo(user.getFirstName()))
                .body("user.middleName", equalTo(user.getMiddleName()))
                .body("user.lastName", equalTo(user.getLastName()))
                .body("user.createdBy", containsStringIgnoringCase("SELF"));
    }

    @Test
    public void test_Get_Self_Details_Success() throws ExecutionException, InterruptedException {
        UserDto user = createTestUser();
        validateResponseOfGetSelfDetails(
                getSelfDetails(getAccessToken(
                        user.getUsername(),
                        user.getPassword()
                )),
                user
        );
    }

    @Test(dependsOnMethods = {"test_Resend_Email_Verification_Link_Success"})
    public void test_Verify_Email_Success(ITestContext context) throws ExecutionException, InterruptedException, MessagingException, IOException {
        String contextAttributeUser = "user_From_test_Resend_Email_Verification_Link_Success";
        UserDto user = (UserDto) context.getAttribute(contextAttributeUser);
        context.removeAttribute(contextAttributeUser);
        verifyEmail(getToken(
                user.getEmail(),
                TEST_EMAIL_PASSWORD,
                "Resending email verification link after registration"
        )).then()
                .statusCode(200)
                .body("message", containsStringIgnoringCase("Email verification successful"));
        getSelfDetails(getAccessToken(
                user.getUsername(),
                user.getPassword()
        )).then()
                .statusCode(200)
                .body("emailVerified", equalTo(true));
    }

    @Test
    public void test_Verify_Email_Failure_Invalid_Token() throws ExecutionException, InterruptedException {
        for (String invalidToken : INVALID_UUIDS) {
            verifyEmail(invalidToken).then()
                    .statusCode(400)
                    .body("message", containsStringIgnoringCase("Invalid email verification token"));
        }
    }

    @Test
    public void test_Resend_Email_Verification_Link_Success(ITestContext context) throws ExecutionException, InterruptedException {
        UserDto user = createRandomUserDtoWithRandomValidEmail();
        context.setAttribute("user_From_test_Resend_Email_Verification_Link_Success", user);
        user.setEmailVerified(false);
        createTestUser(user);
        resendEmailVerificationLink(user.getUsername()).then()
                .statusCode(200)
                .body("message", containsStringIgnoringCase("Email verification link resent successfully. Please check your email"));
    }

    @Test
    public void test_Resend_Email_Verification_Link_Failure_Email_Already_Verified() throws ExecutionException, InterruptedException {
        UserDto user = createTestUser();
        resendEmailVerificationLink(user.getUsername()).then()
                .statusCode(400)
                .body("message", containsStringIgnoringCase("Email is already verified"));
        resendEmailVerificationLink(user.getEmail()).then()
                .statusCode(400)
                .body("message", containsStringIgnoringCase("Email is already verified"));
    }

    @Test
    public void test_Forgot_Password_Failure_Email_Not_Verified() throws ExecutionException, InterruptedException {
        UserDto user = createRandomUserDto();
        user.setEmailVerified(false);
        createTestUser(user);
        forgotPassword(user.getUsername()).then()
                .statusCode(400)
                .body("message", containsStringIgnoringCase("Email is not verified"));
        forgotPassword(user.getEmail()).then()
                .statusCode(400)
                .body("message", containsStringIgnoringCase("Email is not verified"));
    }

    private Map<String, Object> createTestUserAuthenticatorAppMfaEnabled() throws ExecutionException, InterruptedException, NotFoundException, IOException, InvalidKeyException {
        UserDto user = createTestUser();
        String accessToken = getAccessToken(
                user.getUsername(),
                user.getPassword()
        );
        Response response = requestToToggleMfa(
                accessToken,
                AUTHENTICATOR_APP_MFA,
                ENABLE
        );
        response.then()
                .statusCode(200);
        String secret = extractSecretFromByteArrayOfQrCode(response.asByteArray());
        verifyToggleMfa(
                accessToken,
                AUTHENTICATOR_APP_MFA,
                ENABLE,
                generateTotp(secret)
        ).then()
                .statusCode(200);
        return Map.of(
                "user", user,
                "secret", secret
        );
    }

    @Test
    public void test_Reset_Password_Success() throws ExecutionException, InterruptedException, InvalidKeyException, NotFoundException, IOException {
        Map<String, Object> map = createTestUserAuthenticatorAppMfaEnabled();
        UserDto user = (UserDto) map.get("user");
        resetPassword(Map.of(
                        "usernameOrEmail", user.getUsername(),
                        "otpTotp", generateTotp((String) map.get("secret")),
                        "method", AUTHENTICATOR_APP_MFA,
                        "password", "NewPassword@123",
                        "confirmPassword", "NewPassword@123"
                )
        ).then()
                .statusCode(200)
                .body("message", containsStringIgnoringCase("Password reset successful"));
        login(
                user.getUsername(),
                "NewPassword@123"
        ).then()
                .statusCode(200)
                .body("state_token", notNullValue());
    }

    @Test
    public void test_Reset_Password_Failure_Invalid_Input() throws ExecutionException, InterruptedException {
        Map<String, String> map = new HashMap<>();
        map.put("usernameOrEmail", "SomeUsername");
        map.put("method", AUTHENTICATOR_APP_MFA);
        for (String invalidOtp : INVALID_OTPS) {
            map.put("otpTotp", invalidOtp);
            resetPassword(map).then()
                    .statusCode(400)
                    .body("invalid_inputs", not(empty()));
        }
        map.put("otpTotp", "123456");
        for (String invalidPassword : INVALID_PASSWORDS) {
            map.put("password", invalidPassword);
            resetPassword(map).then()
                    .statusCode(400)
                    .body("invalid_inputs", not(empty()));
        }
        map.put("password", "ValidPassword@123");
        map.put("confirmPassword", "DifferentPassword@123");
        resetPassword(map).then()
                .statusCode(400)
                .body("invalid_inputs", not(empty()));
    }

    @Test
    public void test_Change_Password_Success() throws ExecutionException, InterruptedException {
        UserDto user = createTestUser();
        changePassword(
                getAccessToken(
                        user.getUsername(),
                        user.getPassword()
                ),
                Map.of(
                        "oldPassword", user.getPassword(),
                        "password", "NewPassword@123",
                        "confirmPassword", "NewPassword@123"
                )
        ).then()
                .statusCode(200)
                .body("message", containsStringIgnoringCase("Password changed successfully"));
        login(
                user.getUsername(),
                "NewPassword@123"
        ).then()
                .statusCode(200)
                .body("access_token", notNullValue());
    }

    @Test
    public void test_Change_Password_Failure_Invalid_Input() throws ExecutionException, InterruptedException {
        UserDto user = createTestUser();
        String accessToken = getAccessToken(
                user.getUsername(),
                user.getPassword()
        );
        Map<String, String> map = new HashMap<>();
        for (String invalidPassword : INVALID_PASSWORDS) {
            map.put("oldPassword", invalidPassword);
            changePassword(
                    accessToken,
                    map
            ).then()
                    .statusCode(400)
                    .body("invalid_inputs", not(empty()));
        }
        map.put("oldPassword", user.getPassword());
        for (String invalidPassword : INVALID_PASSWORDS) {
            map.put("password", invalidPassword);
            changePassword(
                    accessToken,
                    map
            ).then()
                    .statusCode(400)
                    .body("invalid_inputs", not(empty()));
        }
        map.put("password", "ValidPassword@123");
        map.put("confirmPassword", "DifferentPassword@123");
        changePassword(
                accessToken,
                map
        ).then()
                .statusCode(400)
                .body("invalid_inputs", not(empty()));
    }

    @Test
    public void test_Verify_Change_Password_Success() throws NotFoundException, IOException, ExecutionException, InvalidKeyException, InterruptedException {
        Map<String, Object> map = createTestUserAuthenticatorAppMfaEnabled();
        verifyChangePassword(
                getAccessTokenForUserWhoseAuthenticatorAppMfaIsEnabled(map),
                Map.of(
                        "otpTotp", generateTotp((String) map.get("secret")),
                        "method", AUTHENTICATOR_APP_MFA,
                        "password", "NewPassword@123",
                        "confirmPassword", "NewPassword@123"
                )
        ).then()
                .statusCode(200)
                .body("message", containsStringIgnoringCase("Password changed successfully"));
        UserDto user = (UserDto) map.get("user");
        login(
                user.getUsername(),
                "NewPassword@123"
        ).then()
                .statusCode(200)
                .body("state_token", notNullValue());
    }

    private String getAccessTokenForUserWhoseAuthenticatorAppMfaIsEnabled(Map<String, Object> map) throws ExecutionException, InterruptedException, InvalidKeyException {
        UserDto user = (UserDto) map.get("user");
        Response response = verifyMfaToLogin(
                AUTHENTICATOR_APP_MFA,
                getStateToken(
                        user.getUsername(),
                        user.getPassword()
                ),
                generateTotp((String) map.get("secret"))
        );
        response.then()
                .statusCode(200);
        return response.jsonPath()
                .getString("access_token");
    }

    @Test
    public void test_Verify_Change_Password_Failure_Invalid_Input() throws ExecutionException, InterruptedException {
        UserDto user = createTestUser();
        String accessToken = getAccessToken(
                user.getUsername(),
                user.getPassword()
        );
        Map<String, String> map = new HashMap<>();
        for (String invalidOtp : INVALID_OTPS) {
            map.put("otpTotp", invalidOtp);
            map.put("method", AUTHENTICATOR_APP_MFA);
            verifyChangePassword(
                    accessToken,
                    map
            ).then()
                    .statusCode(400)
                    .body("invalid_inputs", not(empty()));
        }
        map.put("otpTotp", "123456");
        for (String invalidPassword : INVALID_PASSWORDS) {
            map.put("password", invalidPassword);
            verifyChangePassword(
                    accessToken,
                    map
            ).then()
                    .statusCode(400)
                    .body("invalid_inputs", not(empty()));
        }
        map.put("password", "ValidPassword@123");
        map.put("confirmPassword", "DifferentPassword@123");
        verifyChangePassword(
                accessToken,
                map
        ).then()
                .statusCode(400)
                .body("invalid_inputs", not(empty()));
    }

    @Test
    public void test_Email_Change_Request_Success(ITestContext context) throws ExecutionException, InterruptedException {
        UserDto user = createTestUserRandomValidEmail();
        context.setAttribute("user_From_test_Email_Change_Request_Success", user);
        String accessToken = getAccessToken(
                user.getUsername(),
                user.getPassword()
        );
        context.setAttribute("accessToken_From_test_Email_Change_Request_Success", accessToken);
        String newEmail = validRandomEmail();
        context.setAttribute("newEmail_From_test_Email_Change_Request_Success", newEmail);
        emailChangeRequest(
                accessToken,
                newEmail
        ).then()
                .statusCode(200)
                .body("message", containsStringIgnoringCase("Otp's sent to your new & old email. Please check your emails to verify your email change"));
    }

    @Test
    public void test_Email_Change_Request_Failure_Invalid_Input() throws ExecutionException, InterruptedException {
        UserDto user1 = createRandomUserDto();
        UserDto user2 = createRandomUserDto();
        createTestUsers(Set.of(user1, user2));
        String accessToken = getAccessToken(
                user1.getUsername(),
                user1.getPassword()
        );
        for (String invalidEmail : INVALID_EMAILS) {
            emailChangeRequest(
                    accessToken,
                    invalidEmail
            ).then()
                    .statusCode(400);
        }
        emailChangeRequest(
                accessToken,
                user1.getEmail()
        ).then()
                .statusCode(400)
                .body("message", containsStringIgnoringCase("New email cannot be same as current email"));
        emailChangeRequest(
                accessToken,
                user2.getEmail()
        ).then()
                .statusCode(400)
                .body("message", containsStringIgnoringCase("is already taken"));
    }

//    @Test(dependsOnMethods = {"test_Email_Change_Request_Success"})
//    public void test_Verify_Email_Change_Success(ITestContext context) throws ExecutionException, InterruptedException {
//        String contextAttributeUser = "user_From_test_Email_Change_Request_Success";
//        String contextAttributeAccessToken = "accessToken_From_test_Email_Change_Request_Success";
//        String contextAttributeNewEmail = "newEmail_From_test_Email_Change_Request_Success";
//        UserDto user = (UserDto) context.getAttribute(contextAttributeUser);
//        String accessToken = (String) context.getAttribute(contextAttributeAccessToken);
//        String newEmail = (String) context.getAttribute(contextAttributeNewEmail);
//        context.removeAttribute(contextAttributeUser);
//        context.removeAttribute(contextAttributeAccessToken);
//        context.removeAttribute(contextAttributeNewEmail);
//        CompletableFuture<String> newEmailOtpFuture = CompletableFuture.supplyAsync(() ->
//                {
//                    try {
//                        return getOtp(
//                                newEmail,
//                                TEST_EMAIL_PASSWORD,
//                                "Otp for email change in new email"
//                        );
//                    } catch (MessagingException | InterruptedException | IOException ex) {
//                        throw new RuntimeException(ex);
//                    }
//                }
//        );
//        CompletableFuture<String> oldEmailOtpFuture = CompletableFuture.supplyAsync(() ->
//                {
//                    try {
//                        return getOtp(
//                                user.getEmail(),
//                                TEST_EMAIL_PASSWORD,
//                                "Otp for email change in old email"
//                        );
//                    } catch (MessagingException | InterruptedException | IOException ex) {
//                        throw new RuntimeException(ex);
//                    }
//                }
//        );
//        verifyEmailChange(
//                accessToken,
//                newEmailOtpFuture.get(),
//                oldEmailOtpFuture.get(),
//                user.getPassword()
//        ).then()
//                .statusCode(200)
//                .body("message", containsStringIgnoringCase("Email change successful. Please login again to continue"))
//                .body("user.email", equalTo(newEmail));
//    }

    @Test
    public void test_Verify_Email_Change_Failure_Invalid_Input() throws ExecutionException, InterruptedException {
        UserDto user = createTestUser();
        String accessToken = getAccessToken(
                user.getUsername(),
                user.getPassword()
        );
        for (String invalidOtp : INVALID_OTPS) {
            verifyEmailChange(
                    accessToken,
                    invalidOtp,
                    "123456",
                    "Somepassword@001"
            ).then()
                    .statusCode(400)
                    .body("message", containsStringIgnoringCase("Invalid Otp's"));
        }
        for (String invalidOtp : INVALID_OTPS) {
            verifyEmailChange(
                    accessToken,
                    "123456",
                    invalidOtp,
                    "Somepassword@001"
            ).then()
                    .statusCode(400)
                    .body("message", containsStringIgnoringCase("Invalid Otp's"));
        }
        for (String invalidPassword : INVALID_PASSWORDS) {
            verifyEmailChange(
                    accessToken,
                    "123456",
                    "123456",
                    invalidPassword
            ).then()
                    .statusCode(400)
                    .body("message", containsStringIgnoringCase("Invalid password"));
        }
    }

    @Test
    public void test_Delete_Account_Success() throws ExecutionException, InterruptedException {
        UserDto user = createTestUser();
        deleteAccount(getAccessToken(
                        user.getUsername(),
                        user.getPassword()
                ),
                user.getPassword()
        ).then()
                .statusCode(200)
                .body("message", containsStringIgnoringCase("Account deleted successfully"));
        login(
                user.getUsername(),
                user.getPassword()
        ).then()
                .statusCode(401)
                .body("error", containsStringIgnoringCase("Unauthorized"))
                .body("message", containsStringIgnoringCase("Bad credentials"));
    }

    @Test
    public void test_Delete_Account_Failure_Invalid_Password() throws ExecutionException, InterruptedException {
        UserDto user = createTestUser();
        String accessToken = getAccessToken(
                user.getUsername(),
                user.getPassword()
        );
        for (String invalidPassword : INVALID_PASSWORDS) {
            deleteAccount(
                    accessToken,
                    invalidPassword
            ).then()
                    .statusCode(400)
                    .body("message", containsStringIgnoringCase("Invalid password"));
        }
    }

    @Test
    public void test_Verify_Delete_Account_Success() throws NotFoundException, IOException, ExecutionException, InvalidKeyException, InterruptedException {
        Map<String, Object> map = createTestUserAuthenticatorAppMfaEnabled();
        verifyDeleteAccount(
                getAccessTokenForUserWhoseAuthenticatorAppMfaIsEnabled(map),
                generateTotp((String) map.get("secret")),
                AUTHENTICATOR_APP_MFA
        ).then()
                .statusCode(200)
                .body("message", containsStringIgnoringCase("Account deleted successfully"));
        UserDto user = (UserDto) map.get("user");
        login(
                user.getUsername(),
                user.getPassword()
        ).then()
                .statusCode(401)
                .body("error", containsStringIgnoringCase("Unauthorized"))
                .body("message", containsStringIgnoringCase("Bad credentials"));
    }

    @Test
    public void test_Verify_Delete_Account_Failure_Invalid_Input() throws ExecutionException, InterruptedException {
        UserDto user = createTestUser();
        String accessToken = getAccessToken(
                user.getUsername(),
                user.getPassword()
        );
        for (String invalidOtp : INVALID_OTPS) {
            verifyDeleteAccount(
                    accessToken,
                    invalidOtp,
                    AUTHENTICATOR_APP_MFA
            ).then()
                    .statusCode(400)
                    .body("message", containsStringIgnoringCase("Invalid Otp/Totp"));
        }
    }

    @Test
    public void test_Update_Details_Success() throws ExecutionException, InterruptedException {
        UserDto user = createTestUser();
        String accessToken = getAccessToken(
                user.getUsername(),
                user.getPassword()
        );
        Map<String, String> body = new HashMap<>();
        body.put("username", "Updated_" + user.getUsername());
        body.put("firstName", "Updated " + user.getFirstName());
        body.put("oldPassword", user.getPassword());
        updateDetails(
                accessToken,
                body
        ).then()
                .statusCode(200)
                .body("message", containsStringIgnoringCase("User details updated successfully"))
                .body("user.username", equalTo("Updated_" + user.getUsername()))
                .body("user.firstName", equalTo("Updated " + user.getFirstName()))
                .body("user.updatedBy", containsStringIgnoringCase("SELF"));
        user.setUsername("Updated_" + user.getUsername());
    }

    @Test
    public void test_Update_Details_Failure_Invalid_Input() throws ExecutionException, InterruptedException {
        UserDto user = createTestUser();
        String accessToken = getAccessToken(
                user.getUsername(),
                user.getPassword()
        );
        Map<String, String> body = new HashMap<>();
        for (String invalidUsername : INVALID_USERNAMES) {
            body.put("username", invalidUsername);
            updateDetails(
                    accessToken,
                    body
            ).then()
                    .statusCode(400)
                    .body("invalid_inputs", not(empty()));
        }
        body.put("username", "ValidUsername");
        for (String invalidFirstName : INVALID_NAMES) {
            body.put("firstName", invalidFirstName);
            updateDetails(
                    accessToken,
                    body
            ).then()
                    .statusCode(400)
                    .body("invalid_inputs", not(empty()));
        }
        body.put("firstName", "ValidFirstName");
        for (String invalidMiddleName : INVALID_NAMES) {
            body.put("middleName", invalidMiddleName);
            updateDetails(
                    accessToken,
                    body
            ).then()
                    .statusCode(400)
                    .body("invalid_inputs", not(empty()));
        }
        body.put("middleName", "ValidMiddleName");
        for (String invalidLastName : INVALID_NAMES) {
            body.put("lastName", invalidLastName);
            updateDetails(
                    accessToken,
                    body
            ).then()
                    .statusCode(400)
                    .body("invalid_inputs", not(empty()));
        }
        body.put("lastName", "ValidLastName");
        for (String invalidOldPassword : INVALID_PASSWORDS) {
            body.put("oldPassword", invalidOldPassword);
            updateDetails(
                    accessToken,
                    body
            ).then()
                    .statusCode(400)
                    .body("invalid_inputs", not(empty()));
        }
    }
}
