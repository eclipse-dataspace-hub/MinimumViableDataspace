/*
 *  Copyright (c) 2025 Think-it GmbH
 *
 *  This program and the accompanying materials are made available under the
 *  terms of the Apache License, Version 2.0 which is available at
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Contributors:
 *       Think-it GmbH - initial API and implementation
 *
 */

package org.eclipse.edc.mvd.dataplane.signaling;

import okhttp3.Request;
import org.eclipse.dataplane.domain.DataAddress;
import org.eclipse.dataplane.domain.Result;
import org.eclipse.dataplane.domain.dataflow.DataFlow;
import org.eclipse.edc.http.spi.EdcHttpClient;
import org.eclipse.edc.spi.monitor.Monitor;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

/**
 * Small helper class that receives authorization and endpoint information when a data flow enters the "STARTED" state on
 * data planes. It records that information and uses it later to download the data.
 */
public class ConsumerDataHandler {

    private final EdcHttpClient httpClient;
    private final Monitor monitor;
    private final Map<String, DataAddress> ongoingTransfers = new HashMap<>();

    public ConsumerDataHandler(EdcHttpClient httpClient, Monitor monitor) {
        this.httpClient = httpClient;
        this.monitor = monitor;
    }

    public Result<DataFlow> storeDataflow(DataFlow dataFlow) {
        if ("HttpData-PULL".equals(dataFlow.getProfile())) {
            ongoingTransfers.put(dataFlow.getId(), dataFlow.getDataAddress());
            return Result.success(dataFlow);
        } else {
            monitor.warning("Profile %s not supported".formatted(dataFlow.getProfile()));
            return Result.failure(new UnsupportedOperationException("Profile %s not supported".formatted(dataFlow.getProfile())));
        }
    }

    public Result<DataFlow> removeDataflow(DataFlow flow) {
        ongoingTransfers.remove(flow.getId());
        return Result.success(null);
    }

    public Result<String> downloadData(String flowId) {
        var dataAddress = ongoingTransfers.get(flowId);

        if (dataAddress == null) {
            return Result.failure(new IllegalArgumentException("No data flow found for id %s".formatted(flowId)));
        }
        var sourceUri = URI.create(dataAddress.endpoint());
        var token = dataAddress.endpointProperties().stream().filter(ep -> ep.name().equals("access_token"))
                .findFirst()
                .map(DataAddress.EndpointProperty::value)
                .orElseThrow(() -> new IllegalArgumentException("No access token found in data address properties"));
        var request = new Request.Builder()
                .addHeader("Authorization", "Bearer " + token)
                .url(sourceUri.toString())
                .get()
                .build();
        try (var response = httpClient.execute(request)) {
            if (response.isSuccessful()) {
                var body = response.body().string();
                monitor.info("Received data for data flow %s: %s".formatted(flowId, body));
                return Result.success(body);
            } else {
                return Result.failure(new IllegalArgumentException("Data source endpoint responded with %d".formatted(response.code())));
            }
        } catch (RuntimeException e) {
            return Result.failure(e);
        } catch (Exception e) {
            return Result.failure(new RuntimeException("Error retrieving data for data flow %s".formatted(flowId), e));
        }

    }

    public Map<String, DataAddress> getAllFlows() {
        return ongoingTransfers;
    }

    public DataAddress getFlow(String flowId) {
        return ongoingTransfers.get(flowId);
    }
}
