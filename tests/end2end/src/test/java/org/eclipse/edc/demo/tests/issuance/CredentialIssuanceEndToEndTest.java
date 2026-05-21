/*
 *  Copyright (c) 2025 Metaform Systems, Inc.
 *
 *  This program and the accompanying materials are made available under the
 *  terms of the Apache License, Version 2.0 which is available at
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Contributors:
 *       Metaform Systems, Inc. - initial API and implementation
 *
 */

package org.eclipse.edc.demo.tests.issuance;

import io.restassured.specification.RequestSpecification;
import jakarta.ws.rs.core.HttpHeaders;
import org.eclipse.edc.junit.annotations.EndToEndTest;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.eclipse.edc.demo.tests.TestConstants.TEST_POLL_DELAY;
import static org.eclipse.edc.demo.tests.TestConstants.TEST_TIMEOUT_DURATION;

/**
 * This test asserts the DCP Issuance Flow end-to-end where the holder makes a DCP credential request and waits for the credential
 * to be issued by the IssuerService.
 */
@EndToEndTest
public class CredentialIssuanceEndToEndTest {

    private static final String CONSUMER_IDENTITYHUB_IDENTITY_URL = "http://ih.consumer.localhost:8080/cs";
    private static final String PARTICIPANT_CONTEXT_ID = "consumer-participant";
    private static final String ISSUER_DID = "did:web:issuerservice.issuer.svc.cluster.local%3A10016:issuer";
    private static final String HOLDER_PID = UUID.randomUUID().toString();
    private static final String KEYCLOAK_URL = "http://keycloak.localhost:8080";

    private static RequestSpecification baseRequest() {
        return given()
                .header("Authorization", "Bearer " + adminToken())
                .contentType(JSON)
                .baseUri(CONSUMER_IDENTITYHUB_IDENTITY_URL)
                .when();
    }

    private static String adminToken() {
        return given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("client_id", "admin")
                .formParam("client_secret", "edc-v-admin-secret")
                .formParam("grant_type", "client_credentials")
                .post(KEYCLOAK_URL + "/realms/mvd/protocol/openid-connect/token")
                .then()
                .log().ifValidationFails()
                .statusCode(200)
                .extract().jsonPath().getString("access_token");
    }

    @Test
    void makeCredentialRequest_expectCredential() {
        var location = baseRequest()
                .body("""
                        {
                          "issuerDid": "%s",
                          "holderPid": "%s",
                          "credentials": [
                            {"format": "vc20-bssl/jwt", "type": "MembershipCredential", "id": "membership-credential-def"}
                          ]
                        }
                        """.formatted(ISSUER_DID, HOLDER_PID))
                .post("/api/identity/v1alpha/participants/%s/credentials/request".formatted(PARTICIPANT_CONTEXT_ID))
                .then()
                .log().ifValidationFails()
                .statusCode(201)
                .extract().header(HttpHeaders.LOCATION);

        assertThat(location).endsWith(HOLDER_PID);
        var requestId = location.substring(location.lastIndexOf('/') + 1);
        assertThat(requestId).isEqualTo(HOLDER_PID);

        // wait for the state of the request to become ISSUED
        await().atMost(TEST_TIMEOUT_DURATION)
                .pollDelay(TEST_POLL_DELAY)
                .untilAsserted(() -> {
                    baseRequest()
                            .get("/api/identity/v1alpha/participants/%s/credentials/request/%s".formatted(PARTICIPANT_CONTEXT_ID, requestId))
                            .then()
                            .log().ifValidationFails()
                            .statusCode(200)
                            .body("status", Matchers.equalTo("ISSUED"));
                });

        // check that the holder now has credentials in storage
        List<List<String>> list = baseRequest()
                .get("/api/identity/v1alpha/credentials")
                .jsonPath()
                .getList("verifiableCredential.credential.type");

        assertThat(list).anySatisfy(typesList ->
                assertThat(typesList).containsExactlyInAnyOrder("MembershipCredential", "VerifiableCredential"));
    }
}
