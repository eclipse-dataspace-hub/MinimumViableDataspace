/*
 *  Copyright (c) 2026 Metaform Systems, Inc.
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

package org.eclipse.edc.mvd.dataplane;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.dataplane.Dataplane;
import org.eclipse.dataplane.domain.DataAddress;
import org.eclipse.dataplane.domain.Result;
import org.eclipse.dataplane.domain.dataflow.DataFlow;
import org.eclipse.dataplane.domain.registration.Oauth2ClientCredentialsAuthorization;
import org.eclipse.dataplane.port.DataPlaneRegistrationApiController;
import org.eclipse.dataplane.port.DataPlaneSignalingApiController;
import org.eclipse.edc.http.spi.EdcHttpClient;
import org.eclipse.edc.keys.KeyParserRegistryImpl;
import org.eclipse.edc.keys.LocalPublicKeyServiceImpl;
import org.eclipse.edc.keys.VaultPrivateKeyResolver;
import org.eclipse.edc.keys.keyparsers.JwkParser;
import org.eclipse.edc.mvd.dataplane.data.ConsumerProxyController;
import org.eclipse.edc.mvd.dataplane.data.DataPlanePublicApiController;
import org.eclipse.edc.mvd.dataplane.signaling.ConsumerDataHandler;
import org.eclipse.edc.mvd.dataplane.signaling.NoneAuthorization;
import org.eclipse.edc.runtime.metamodel.annotation.Configuration;
import org.eclipse.edc.runtime.metamodel.annotation.Extension;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.runtime.metamodel.annotation.Setting;
import org.eclipse.edc.runtime.metamodel.annotation.Settings;
import org.eclipse.edc.security.token.jwt.DefaultJwsSignerProvider;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.security.Vault;
import org.eclipse.edc.spi.system.Hostname;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;
import org.eclipse.edc.token.JwtGenerationService;
import org.eclipse.edc.token.TokenValidationServiceImpl;
import org.eclipse.edc.token.spi.TokenGenerationService;
import org.eclipse.edc.web.spi.WebService;
import org.eclipse.edc.web.spi.configuration.ApiContext;
import org.eclipse.edc.web.spi.configuration.PortMapping;
import org.eclipse.edc.web.spi.configuration.PortMappingRegistry;
import org.jetbrains.annotations.NotNull;

import java.net.URI;
import java.util.List;

@Extension(value = SignalingDataPlaneRuntimeExtension.NAME)
public class SignalingDataPlaneRuntimeExtension implements ServiceExtension {

    public static final String NAME = "Signaling pata plane Extension";
    public static final String APICONTEXT_PUBLIC = "public";
    public static final String APICONTEXT_PROXY = "proxy";

    @Setting(key = "dataplane.id")
    private String dataplaneId;
    @Setting(key = "edc.transfer.proxy.token.signer.privatekey.alias")
    private String privateKeyId;
    @Setting(key = "edc.transfer.proxy.token.verifier.publickey.alias")
    private String publicKeyId;
    @Configuration
    private SignalingApiConfig signalingApiConfig;

    @Configuration
    private PublicApiConfig publicApiConfig;

    @Configuration
    private ProxyApiConfig proxyApiConfig;

    @Inject
    private WebService webService;
    @Inject
    private Monitor monitor;

    private Dataplane dataplane;
    private ConsumerDataHandler dataFetcher;
    @Inject
    private PortMappingRegistry portMappingRegistry;

    @Inject
    private Hostname hostname;

    @Inject
    private EdcHttpClient httpClient;

    @Inject
    private Vault vault;

    private TokenGenerationService tokenGenerationService;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void initialize(ServiceExtensionContext context) {
        dataFetcher = new ConsumerDataHandler(httpClient, monitor);

        var builder = Dataplane.newInstance()
                .id(dataplaneId)
                .registerAuthorization(new Oauth2ClientCredentialsAuthorization())
                .registerAuthorization(new NoneAuthorization())
                .endpoint(signalingApiConfig.dataFlowEndpoint(hostname.get()))
                .profile("HttpData-PULL")
                .onPrepare(this::prepare)
                .onStart(this::startDataFlow)
                .onStarted(this::requestData)
                .onSuspend(Result::success) // suspension not implemented
                .onResume(flow -> flow.getType() == DataFlow.Type.PROVIDER
                        ? startDataFlow(flow)
                        : dataFetcher.storeDataflow(flow))
                .onCompleted(df -> dataplane.notifyCompleted(df.getId()).map(u -> df))
                .onTerminate(dataFetcher::removeDataflow);

        dataplane = builder.build();

        //initialize token services
        var registry = new KeyParserRegistryImpl();
        registry.register(new JwkParser(new ObjectMapper(), context.getMonitor()));
        var signerProvider = new DefaultJwsSignerProvider(new VaultPrivateKeyResolver(registry, vault, context.getMonitor(), context.getConfig()));
        tokenGenerationService = new JwtGenerationService(signerProvider);
        var tokenValidationService = new TokenValidationServiceImpl();
        var publicKeyResolver = new LocalPublicKeyServiceImpl(vault, registry);

        var portMapping = new PortMapping(ApiContext.SIGNALING, signalingApiConfig.port(), signalingApiConfig.path());
        portMappingRegistry.register(portMapping);

        var publicPortMapping = new PortMapping(APICONTEXT_PUBLIC, publicApiConfig.port(), publicApiConfig.path());
        portMappingRegistry.register(publicPortMapping);

        var proxyPortMapping = new PortMapping(APICONTEXT_PROXY, proxyApiConfig.port(), proxyApiConfig.path());
        portMappingRegistry.register(proxyPortMapping);

        webService.registerResource(ApiContext.SIGNALING, new DataPlaneSignalingApiController(dataplane));
        webService.registerResource(ApiContext.SIGNALING, new DataPlaneRegistrationApiController(dataplane));
        webService.registerResource(APICONTEXT_PUBLIC, new DataPlanePublicApiController(httpClient, monitor, tokenValidationService, publicKeyResolver));
        webService.registerResource(APICONTEXT_PROXY, new ConsumerProxyController(dataFetcher));
    }

    private Result<DataFlow> requestData(DataFlow dataFlow) {
        return dataFetcher.storeDataflow(dataFlow)
                .onFailure(throwable -> {
                    dataplane.notifyErrored(dataFlow.getId(), throwable);
                });
    }

    private Result<DataFlow> prepare(DataFlow dataFlow) {
        return "HttpData-PULL".equals(dataFlow.getProfile())
                ? Result.success(dataFlow)
                : Result.failure(new UnsupportedOperationException("unsupported profile: " + dataFlow.getProfile()));
    }

    private @NotNull Result<DataFlow> startDataFlow(DataFlow dataFlow) {
        if (dataFlow.getProfile() == null) {
            return Result.failure(new UnsupportedOperationException("no profile specified on data flow " + dataFlow.getId()));
        }
        switch (dataFlow.getProfile()) {
            case "NonFinite-PULL", "Finite-PULL", "HttpData-PULL" -> {

                var token = tokenGenerationService.generate(privateKeyId,
                        tokenParameters -> tokenParameters.header("kid", publicKeyId),
                        tokenParameters -> tokenParameters.claims("sub", dataFlow.getId()),
                        tokenParameters -> tokenParameters.claims("scope", "dataflow"),
                        tokenParameters -> tokenParameters.claims("aud", dataplaneId),
                        tokenParameters -> tokenParameters.claims("iss", dataplaneId)
                );

                if (token.succeeded()) {
                    var dataAddress = new DataAddress("http",
                            publicApiConfig.dataSourceEndpoint(hostname.get(), dataFlow.getId()),
                            List.of(new DataAddress.EndpointProperty("authorization", "access_token", token.getContent().getToken())));
                    dataFlow.setDataAddress(dataAddress);
                    return Result.success(dataFlow);
                } else {
                    return Result.failure(new RuntimeException("Token generation failed for data flow: " + dataFlow.getId()));
                }


            }
            default -> {
                return Result.failure(new RuntimeException("Profile %s not supported".formatted(dataFlow.getProfile())));
            }
        }
    }

    @Settings
    public record SignalingApiConfig(
            @Setting(key = "web.http." + ApiContext.SIGNALING + ".path") String path,
            @Setting(key = "web.http." + ApiContext.SIGNALING + ".port") int port
    ) {

        public URI dataFlowEndpoint(String hostname) {
            return URI.create("http://%s:%d%s/v1/dataflows".formatted(hostname, port, path));
        }
    }

    @Settings
    public record PublicApiConfig(
            @Setting(key = "web.http." + APICONTEXT_PUBLIC + ".path", defaultValue = "/api/public") String path,
            @Setting(key = "web.http." + APICONTEXT_PUBLIC + ".port", defaultValue = "11002") int port) {
        public String dataSourceEndpoint(String hostname, String dataFlowId) {
            return "http://%s:%d%s/%s/data/source".formatted(hostname, port, path, dataFlowId);
        }
    }

    @Settings
    public record ProxyApiConfig(
            @Setting(key = "web.http." + APICONTEXT_PROXY + ".path", defaultValue = "/api/proxy") String path,
            @Setting(key = "web.http." + APICONTEXT_PROXY + ".port", defaultValue = "11003") int port) {
    }
}
