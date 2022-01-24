/**
 * Copyright (C) 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.notifier.webhook;

import io.gravitee.common.http.HttpStatusCode;
import io.gravitee.notifier.api.AbstractConfigurableNotifier;
import io.gravitee.notifier.api.Notification;
import io.gravitee.notifier.api.exception.NotifierException;
import io.gravitee.notifier.webhook.configuration.WebhookNotifierConfiguration;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.*;
import io.vertx.core.net.ProxyOptions;
import io.vertx.core.net.ProxyType;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.springframework.beans.factory.annotation.Value;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
public class WebhookNotifier extends AbstractConfigurableNotifier<WebhookNotifierConfiguration> {

    private static final String TYPE = "webhook-notifier";

    private static final String HTTPS_SCHEME = "https";

    @Value("${httpClient.timeout:10000}")
    private int httpClientTimeout;

    @Value("${httpClient.proxy.type:HTTP}")
    private String httpClientProxyType;

    @Value("${httpClient.proxy.http.host:#{systemProperties['http.proxyHost'] ?: 'localhost'}}")
    private String httpClientProxyHttpHost;

    @Value("${httpClient.proxy.http.port:#{systemProperties['http.proxyPort'] ?: 3128}}")
    private int httpClientProxyHttpPort;

    @Value("${httpClient.proxy.http.username:#{null}}")
    private String httpClientProxyHttpUsername;

    @Value("${httpClient.proxy.http.password:#{null}}")
    private String httpClientProxyHttpPassword;

    @Value("${httpClient.proxy.https.host:#{systemProperties['https.proxyHost'] ?: 'localhost'}}")
    private String httpClientProxyHttpsHost;

    @Value("${httpClient.proxy.https.port:#{systemProperties['https.proxyPort'] ?: 3128}}")
    private int httpClientProxyHttpsPort;

    @Value("${httpClient.proxy.https.username:#{null}}")
    private String httpClientProxyHttpsUsername;

    @Value("${httpClient.proxy.https.password:#{null}}")
    private String httpClientProxyHttpsPassword;

    public WebhookNotifier(WebhookNotifierConfiguration configuration) {
        super(TYPE, configuration);
    }

    @Override
    protected CompletableFuture<Void> doSend(Notification notification, Map<String, Object> parameters) {
        CompletableFuture<Void> future = new CompletableFuture<>();

        URI target = URI.create(configuration.getUrl());

        HttpClientOptions options = new HttpClientOptions();

        if (HTTPS_SCHEME.equalsIgnoreCase(target.getScheme())) {
            options.setSsl(true).setTrustAll(true).setVerifyHost(false);
        }

        options.setMaxPoolSize(1).setKeepAlive(false).setTcpKeepAlive(false).setConnectTimeout(httpClientTimeout);

        if (configuration.isUseSystemProxy()) {
            ProxyOptions proxyOptions = new ProxyOptions();
            proxyOptions.setType(ProxyType.valueOf(httpClientProxyType));
            if (HTTPS_SCHEME.equals(target.getScheme())) {
                proxyOptions.setHost(httpClientProxyHttpsHost);
                proxyOptions.setPort(httpClientProxyHttpsPort);
                proxyOptions.setUsername(httpClientProxyHttpsUsername);
                proxyOptions.setPassword(httpClientProxyHttpsPassword);
            } else {
                proxyOptions.setHost(httpClientProxyHttpHost);
                proxyOptions.setPort(httpClientProxyHttpPort);
                proxyOptions.setUsername(httpClientProxyHttpUsername);
                proxyOptions.setPassword(httpClientProxyHttpPassword);
            }
            options.setProxyOptions(proxyOptions);
        }

        options.setDefaultPort(target.getPort() != -1 ? target.getPort() : (HTTPS_SCHEME.equals(target.getScheme()) ? 443 : 80));
        options.setDefaultHost(target.getHost());

        HttpClient client = Vertx.currentContext().owner().createHttpClient(options);

        RequestOptions requestOpts = new RequestOptions()
            .setURI(target.getPath())
            .setMethod(convert(configuration.getMethod()))
            .setFollowRedirects(true)
            .setTimeout(httpClientTimeout);

        client
            .request(requestOpts)
            .onFailure(throwable -> handleFailure(future, client, throwable))
            .onSuccess(httpClientRequest -> {
                try {
                    // Connection is made, lets continue.
                    final Future<HttpClientResponse> futureResponse;

                    if (configuration.getHeaders() != null) {
                        configuration.getHeaders().forEach(header -> httpClientRequest.putHeader(header.getName(), header.getValue()));
                    }

                    if (configuration.getBody() != null && !configuration.getBody().isEmpty()) {
                        String body = templatize(configuration.getBody(), parameters);
                        httpClientRequest.headers().remove(HttpHeaders.TRANSFER_ENCODING);
                        httpClientRequest.headers().remove(HttpHeaders.CONTENT_LENGTH);
                        futureResponse = httpClientRequest.send(Buffer.buffer(body));
                    } else {
                        futureResponse = httpClientRequest.send();
                    }

                    futureResponse
                        .onSuccess(httpResponse -> handleSuccess(future, client, httpResponse))
                        .onFailure(throwable -> handleFailure(future, client, throwable));
                } catch (Exception e) {
                    handleFailure(future, client, e);
                }
            });

        return future;
    }

    private void handleSuccess(CompletableFuture<Void> future, HttpClient client, HttpClientResponse httpResponse) {
        if (httpResponse.statusCode() == HttpStatusCode.OK_200) {
            httpResponse.bodyHandler(buffer -> {
                logger.info("Webhook sent!");
                future.complete(null);

                // Close client
                client.close();
            });
        } else {
            logger.error(
                "Unable to send request to webhook at {} / status {} and message {}",
                configuration.getUrl(),
                httpResponse.statusCode(),
                httpResponse.statusMessage()
            );
            future.completeExceptionally(
                new NotifierException(
                    "Unable to send request to '" +
                    configuration.getUrl() +
                    "'. Status code: " +
                    httpResponse.statusCode() +
                    ". Message: " +
                    httpResponse.statusMessage(),
                    null
                )
            );

            // Close client
            client.close();
        }
    }

    private void handleFailure(CompletableFuture<Void> future, HttpClient client, Throwable throwable) {
        try {
            logger.error("Unable to send request to webhook at " + configuration.getUrl() + " cause " + throwable.getMessage());
            future.completeExceptionally(new NotifierException("Unable to send request to '" + configuration.getUrl(), throwable));

            // Close client
            client.close();
        } catch (IllegalStateException ise) {
            // Do not take care about exception when closing client
        }
    }

    private HttpMethod convert(io.gravitee.common.http.HttpMethod httpMethod) {
        switch (httpMethod) {
            case CONNECT:
                return HttpMethod.CONNECT;
            case DELETE:
                return HttpMethod.DELETE;
            case GET:
                return HttpMethod.GET;
            case HEAD:
                return HttpMethod.HEAD;
            case OPTIONS:
                return HttpMethod.OPTIONS;
            case PATCH:
                return HttpMethod.PATCH;
            case POST:
                return HttpMethod.POST;
            case PUT:
                return HttpMethod.PUT;
            case TRACE:
                return HttpMethod.TRACE;
            case OTHER:
                return HttpMethod.valueOf("OTHER");
        }

        return null;
    }
}
