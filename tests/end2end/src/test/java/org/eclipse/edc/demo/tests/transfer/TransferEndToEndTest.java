/*
 *  Copyright (c) 2024 Metaform Systems, Inc.
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

package org.eclipse.edc.demo.tests.transfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.specification.RequestSpecification;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import org.eclipse.edc.jsonld.TitaniumJsonLd;
import org.eclipse.edc.jsonld.spi.JsonLd;
import org.eclipse.edc.junit.annotations.EndToEndTest;
import org.eclipse.edc.junit.testfixtures.TestUtils;
import org.eclipse.edc.spi.monitor.ConsoleMonitor;
import org.eclipse.edc.transform.TypeTransformerRegistryImpl;
import org.eclipse.edc.transform.spi.TypeTransformerRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.eclipse.edc.demo.tests.TestConstants.TEST_POLL_DELAY;
import static org.eclipse.edc.demo.tests.TestConstants.TEST_TIMEOUT_DURATION;

/**
 * This test is designed to run against an MVD deployed in a Kubernetes cluster, with an active ingress controller.
 * The cluster MUST be deployed and seeded according to the README before running this test!
 */
@EndToEndTest
public class TransferEndToEndTest {
    // Management API base URL of the consumer connector, goes through Ingress controller
    private static final String CONSUMER_MANAGEMENT_URL = "http://cp.consumer.localhost";
    // DSP service URL of the provider, not reachable outside the cluster
    private static final String PROVIDER_DSP_URL = "http://controlplane.provider.svc.cluster.local:8082/api/dsp/2025-1";
    // DID of the provider company
    private static final String PROVIDER_ID = "did:web:identityhub.provider.svc.cluster.local%3A7083:provider";
    private static final String CONSUMER_PROXY_URL = "http://dp.consumer.localhost/api/proxy";
    private static final String PROVIDER_MANAGEMENT_URL = "http://cp.provider.localhost";

    private final TypeTransformerRegistry transformerRegistry = new TypeTransformerRegistryImpl();
    private final JsonLd jsonLd = new TitaniumJsonLd(new ConsoleMonitor());
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static RequestSpecification baseRequest() {
        return given()
                .header("X-Api-Key", "password")
                .contentType(JSON)
                .when();
    }

    @DisplayName("Tests a successful End-to-End contract negotiation and data transfer")
    @Test
    void transferData_hasPermission_shouldTransferData() {
        var catalogRequestBody = Json.createObjectBuilder()
                .add("@context", Json.createObjectBuilder().add("edc", "https://w3id.org/edc/connector/management/v2"))
                .add("@type", "CatalogRequest")
                .add("counterPartyId", PROVIDER_ID)
                .add("counterPartyAddress", "http://controlplane.provider.svc.cluster.local:8082/api/dsp/2025-1")
                .add("protocol", "dataspace-protocol-http:2025-1")
                .add("querySpec", Json.createObjectBuilder().build())
                .build();
        var offerId = new AtomicReference<String>();
        // get catalog, extract offer ID
        await().atMost(TEST_TIMEOUT_DURATION)
                .pollDelay(TEST_POLL_DELAY)
                .untilAsserted(() -> {
                    var res = baseRequest()
                            .body(catalogRequestBody)
                            .post(CONSUMER_MANAGEMENT_URL + "/api/mgmt/v4/catalog/request")
                            .then()
                            .log().ifValidationFails()
                            .statusCode(200)
                            .extract().body().as(JsonObject.class);

                    // todo: parse asset offer ID, parse JSON
                    var cat = objectMapper.readValue(res.toString(), CatalogResponse.class);
                    var oid = cat.getDatasets().stream().filter(ds -> ds.getId().equals("asset-1"))
                            .flatMap(ds -> ds.getPolicies().stream())
                            .map(CatalogResponse.Offer::getId)
                            .findFirst().orElseThrow(() -> new AssertionError("No offer found for asset-1"));
                    offerId.set(oid);
                });

        System.out.println("Initiate contract negotiation");

        // initiate negotiation
        var negotiationRequest = TestUtils.getResourceFileContentAsString("negotiation-request.json")
                .replace("{{PROVIDER_ID}}", PROVIDER_ID)
                .replace("{{PROVIDER_DSP_URL}}", PROVIDER_DSP_URL)
                .replace("{{OFFER_ID}}", offerId.get());
        var negotiationId = baseRequest()
                .body(negotiationRequest)
                .post(CONSUMER_MANAGEMENT_URL + "/api/mgmt/v4/contractnegotiations")
                .then()
                .log().ifError()
                .statusCode(200)
                .extract().body().jsonPath().getString("@id");
        assertThat(negotiationId).isNotNull();

        System.out.println("Wait until negotiation is FINALIZED");
        //wait until negotiation is FINALIZED
        var agreementId = new AtomicReference<String>();
        await().atMost(TEST_TIMEOUT_DURATION)
                .pollDelay(TEST_POLL_DELAY)
                .untilAsserted(() -> {
                    var jp = baseRequest()
                            .get(CONSUMER_MANAGEMENT_URL + "/api/mgmt/v4/contractnegotiations/" + negotiationId)
                            .then()
                            .statusCode(200)
                            .extract().body().jsonPath();
                    var state = jp.getString("state");
                    assertThat(state).isEqualTo("FINALIZED");
                    agreementId.set(jp.getString("contractAgreementId"));
                });

        System.out.println("Start transfer process");
        //start transfer process
        var tpRequest = TestUtils.getResourceFileContentAsString("transfer-request.json")
                .replace("{{PROVIDER_ID}}", PROVIDER_ID)
                .replace("{{PROVIDER_DSP_URL}}", PROVIDER_DSP_URL)
                .replace("{{CONTRACT_ID}}", agreementId.get());

        var transferProcessId = baseRequest()
                .body(tpRequest)
                .post(CONSUMER_MANAGEMENT_URL + "/api/mgmt/v4/transferprocesses")
                .then()
                .log().ifError()
                .statusCode(200)
                .extract().body().jsonPath().getString("@id");

        // wait until transfer process is in STARTED state
        System.out.println("Wait until transfer process is STARTED");
        await().atMost(TEST_TIMEOUT_DURATION)
                .pollDelay(TEST_POLL_DELAY)
                .untilAsserted(() -> {
                    var jp = baseRequest()
                            .get(CONSUMER_MANAGEMENT_URL + "/api/mgmt/v4/transferprocesses/%s/state".formatted(transferProcessId))
                            .then()
                            .statusCode(200)
                            .extract().body().jsonPath();

                    assertThat(jp.getString("state")).contains("STARTED");
                });

        System.out.printf("Fetch dataflow with ID %s from custom proxy%n", transferProcessId);
        var endpoint = new AtomicReference<String>();
        var token = new AtomicReference<String>();
        await().atMost(TEST_TIMEOUT_DURATION)
                .pollDelay(TEST_POLL_DELAY)
                .untilAsserted(() -> {
                    var jp = baseRequest()
                            .get(CONSUMER_PROXY_URL + "/flows/%s".formatted(transferProcessId))
                            .then()
                            .log().ifValidationFails()
                            .statusCode(200)
                            .onFailMessage("Expected to find a DataFlow ID %s but did not!".formatted(transferProcessId))
                            .extract().body().jsonPath();

                    endpoint.set(jp.getString("endpoint"));
                    token.set(jp.get("endpointProperties.find { it.name == 'access_token' }.value"));

                    assertThat(endpoint.get()).isNotNull().matches(".*/api/public/.*/data/source");
                    assertThat(token.get()).isNotNull();
                });

        //download exemplary JSON data from public endpoint
        var response = given()
                .header("Authorization", token.get())
                .get(CONSUMER_PROXY_URL + "/flows/%s/data".formatted(transferProcessId))
                .then()
                .log().ifError()
                .statusCode(200)
                .extract().body().as(Object[].class);

        assertThat(response).isNotEmpty();
    }

    @DisplayName("Tests a failing End-to-End contract negotiation because of an unfulfilled policy")
    @Test
    void transferData_doesNotHavePermission_shouldTerminate() {
        var catalogRequestBody = Json.createObjectBuilder()
                .add("@context", Json.createObjectBuilder().add("edc", "https://w3id.org/edc/connector/management/v2"))
                .add("@type", "CatalogRequest")
                .add("counterPartyId", PROVIDER_ID)
                .add("counterPartyAddress", "http://controlplane.provider.svc.cluster.local:8082/api/dsp/2025-1")
                .add("protocol", "dataspace-protocol-http:2025-1")
                .add("querySpec", Json.createObjectBuilder().build())
                .build();
        var offerId = new AtomicReference<String>();
        // get catalog, extract offer ID
        await().atMost(TEST_TIMEOUT_DURATION)
                .pollDelay(TEST_POLL_DELAY)
                .untilAsserted(() -> {
                    var res = baseRequest()
                            .body(catalogRequestBody)
                            .post(CONSUMER_MANAGEMENT_URL + "/api/mgmt/v4/catalog/request")
                            .then()
                            .log().ifValidationFails()
                            .statusCode(200)
                            .extract().body().as(JsonObject.class);

                    // todo: parse asset offer ID, parse JSON
                    var cat = objectMapper.readValue(res.toString(), CatalogResponse.class);
                    var oid = cat.getDatasets().stream().filter(ds -> ds.getId().equals("asset-2"))
                            .flatMap(ds -> ds.getPolicies().stream())
                            .map(CatalogResponse.Offer::getId)
                            .findFirst().orElseThrow(() -> new AssertionError("No offer found for asset-2"));
                    offerId.set(oid);
                });

        System.out.println("Initiate contract negotiation");

        // initiate negotiation
        var negotiationRequest = TestUtils.getResourceFileContentAsString("negotiation-request_invalid.json")
                .replace("{{PROVIDER_ID}}", PROVIDER_ID)
                .replace("{{PROVIDER_DSP_URL}}", PROVIDER_DSP_URL)
                .replace("{{OFFER_ID}}", offerId.get());
        var negotiationId = baseRequest()
                .body(negotiationRequest)
                .post(CONSUMER_MANAGEMENT_URL + "/api/mgmt/v4/contractnegotiations")
                .then()
                .log().ifError()
                .statusCode(200)
                .extract().body().jsonPath().getString("@id");
        assertThat(negotiationId).isNotNull();

        System.out.println("Wait until negotiation is TERMINATED");

        //wait until negotiation is TERMINATED
        await().atMost(TEST_TIMEOUT_DURATION)
                .pollDelay(TEST_POLL_DELAY)
                .untilAsserted(() -> {
                    var jp = baseRequest()
                            .get(CONSUMER_MANAGEMENT_URL + "/api/mgmt/v4/contractnegotiations/" + negotiationId)
                            .then()
                            .statusCode(200)
                            .extract().body().jsonPath();
                    var state = jp.getString("state");
                    assertThat(state).isEqualTo("TERMINATED");
                });
    }
}
