/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.coyote.http11;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.apache.coyote.AbstractProtocol;
import org.apache.coyote.CompressionConfig;
import org.apache.coyote.ContinueResponseTiming;
import org.apache.coyote.Processor;
import org.apache.coyote.Request;
import org.apache.coyote.Response;
import org.apache.tomcat.util.buf.StringUtils;
import org.apache.tomcat.util.http.parser.HttpParser;
import org.apache.tomcat.util.net.AbstractEndpoint;
import org.apache.tomcat.util.res.StringManager;

/**
 * Base implementation of the HTTP/1.1 and HTTP/1.0 protocols.
 *
 * @param <S> The socket type
 */
public abstract class AbstractHttp11Protocol<S> extends AbstractProtocol<S> {

    /**
     * The StringManager for this package.
     */
    protected static final StringManager sm = StringManager.getManager(AbstractHttp11Protocol.class);

    private final CompressionConfig compressionConfig = new CompressionConfig();

    private HttpParser httpParser = null;

    /**
     * Construct a specific instance of this protocol handler.
     *
     * @param endpoint Endpoint on which to accept connections
     */
    public AbstractHttp11Protocol(AbstractEndpoint<S,?> endpoint) {
        super(endpoint);
        setConnectionTimeout(Constants.DEFAULT_CONNECTION_TIMEOUT);
    }


    @Override
    public void init() throws Exception {
        httpParser = new HttpParser(relaxedPathChars, relaxedQueryChars);

        super.init();
    }


    @Override
    protected String getProtocolName() {
        return "Http";
    }


    /**
     * {@inheritDoc}
     * <p>
     * Over-ridden here to make the method visible to nested classes.
     */
    @Override
    protected AbstractEndpoint<S,?> getEndpoint() {
        return super.getEndpoint();
    }


    /**
     * Get the HttpParser used to parse relaxed characters.
     *
     * @return the HttpParser
     */
    public HttpParser getHttpParser() {
        return httpParser;
    }


    // ------------------------------------------------ HTTP specific properties
    // ------------------------------------------ managed in the ProtocolHandler

    private ContinueResponseTiming continueResponseTiming = ContinueResponseTiming.IMMEDIATELY;

    /**
     * Get the setting for how responses to 100-Continue requests will be sent.
     *
     * @return The current continue response timing setting
     */
    public String getContinueResponseTiming() {
        return continueResponseTiming.toString();
    }

    /**
     * Set the setting for how responses to 100-Continue requests will be sent.
     *
     * @param continueResponseTiming The new continue response timing setting
     */
    public void setContinueResponseTiming(String continueResponseTiming) {
        this.continueResponseTiming = ContinueResponseTiming.fromString(continueResponseTiming);
    }

    /**
     * Get the continue response timing setting as an enum value.
     *
     * @return The continue response timing
     */
    public ContinueResponseTiming getContinueResponseTimingInternal() {
        return continueResponseTiming;
    }


    private boolean useKeepAliveResponseHeader = true;

    /**
     * Get the flag that controls whether the Keep-Alive response header will be used.
     *
     * @return {@code true} if the Keep-Alive response header will be used
     */
    public boolean getUseKeepAliveResponseHeader() {
        return useKeepAliveResponseHeader;
    }

    /**
     * Set the flag that controls whether the Keep-Alive response header will be used.
     *
     * @param useKeepAliveResponseHeader {@code true} if the Keep-Alive response header should be used
     */
    public void setUseKeepAliveResponseHeader(boolean useKeepAliveResponseHeader) {
        this.useKeepAliveResponseHeader = useKeepAliveResponseHeader;
    }


    private String relaxedPathChars = null;

    /**
     * Get the set of characters allowed in a URL path.
     *
     * @return The allowed path characters
     */
    public String getRelaxedPathChars() {
        return relaxedPathChars;
    }

    /**
     * Set the set of characters allowed in a URL path.
     *
     * @param relaxedPathChars The allowed path characters
     */
    public void setRelaxedPathChars(String relaxedPathChars) {
        this.relaxedPathChars = relaxedPathChars;
    }


    private String relaxedQueryChars = null;

    /**
     * Get the set of characters allowed in a URL query string.
     *
     * @return The allowed query characters
     */
    public String getRelaxedQueryChars() {
        return relaxedQueryChars;
    }

    /**
     * Set the set of characters allowed in a URL query string.
     *
     * @param relaxedQueryChars The allowed query characters
     */
    public void setRelaxedQueryChars(String relaxedQueryChars) {
        this.relaxedQueryChars = relaxedQueryChars;
    }


    private int maxSavePostSize = 4 * 1024;

    /**
     * Return the maximum size of the post which will be saved during FORM or CLIENT-CERT authentication.
     *
     * @return The size in bytes
     */
    public int getMaxSavePostSize() {
        return maxSavePostSize;
    }

    /**
     * Set the maximum size of a POST which will be buffered during FORM or CLIENT-CERT authentication. When a POST is
     * received where the security constraints require a client certificate, the POST body needs to be buffered while an
     * SSL handshake takes place to obtain the certificate. A similar buffering is required during FORM auth.
     *
     * @param maxSavePostSize The maximum size POST body to buffer in bytes
     */
    public void setMaxSavePostSize(int maxSavePostSize) {
        this.maxSavePostSize = maxSavePostSize;
    }


    /**
     * Maximum size of the HTTP message header.
     */
    private int maxHttpHeaderSize = 8 * 1024;

    /**
     * Get the maximum size of the HTTP message header.
     *
     * @return The maximum size of the HTTP message header in bytes
     */
    public int getMaxHttpHeaderSize() {
        return maxHttpHeaderSize;
    }

    /**
     * Set the maximum size of the HTTP message header.
     *
     * @param valueI The maximum size of the HTTP message header in bytes
     */
    public void setMaxHttpHeaderSize(int valueI) {
        maxHttpHeaderSize = valueI;
    }


    /**
     * Maximum size of the HTTP request message header.
     */
    private int maxHttpRequestHeaderSize = -1;

    /**
     * Get the maximum size of the HTTP request message header.
     *
     * @return The maximum size of the HTTP request message header in bytes
     */
    public int getMaxHttpRequestHeaderSize() {
        return maxHttpRequestHeaderSize == -1 ? getMaxHttpHeaderSize() : maxHttpRequestHeaderSize;
    }

    /**
     * Set the maximum size of the HTTP request message header.
     *
     * @param valueI The maximum size of the HTTP request message header in bytes
     */
    public void setMaxHttpRequestHeaderSize(int valueI) {
        maxHttpRequestHeaderSize = valueI;
    }


    /**
     * Maximum size of the HTTP response message header.
     */
    private int maxHttpResponseHeaderSize = -1;

    /**
     * Get the maximum size of the HTTP response message header.
     *
     * @return The maximum size of the HTTP response message header in bytes
     */
    public int getMaxHttpResponseHeaderSize() {
        return maxHttpResponseHeaderSize == -1 ? getMaxHttpHeaderSize() : maxHttpResponseHeaderSize;
    }

    /**
     * Set the maximum size of the HTTP response message header.
     *
     * @param valueI The maximum size of the HTTP response message header in bytes
     */
    public void setMaxHttpResponseHeaderSize(int valueI) {
        maxHttpResponseHeaderSize = valueI;
    }


    private int connectionUploadTimeout = 300000;

    /**
     * Specifies a different (usually longer) connection timeout during data upload. Default is 5 minutes as in Apache
     * HTTPD server.
     *
     * @return The timeout in milliseconds
     */
    public int getConnectionUploadTimeout() {
        return connectionUploadTimeout;
    }

    /**
     * Set the upload timeout.
     *
     * @param timeout Upload timeout in milliseconds
     */
    public void setConnectionUploadTimeout(int timeout) {
        connectionUploadTimeout = timeout;
    }


    private boolean disableUploadTimeout = true;

    /**
     * Get the flag that controls upload time-outs. If true, the connectionUploadTimeout will be ignored and the regular
     * socket timeout will be used for the full duration of the connection.
     *
     * @return {@code true} if the separate upload timeout is disabled
     */
    public boolean getDisableUploadTimeout() {
        return disableUploadTimeout;
    }

    /**
     * Set the flag to control whether a separate connection timeout is used during upload of a request body.
     *
     * @param isDisabled {@code true} if the separate upload timeout should be disabled
     */
    public void setDisableUploadTimeout(boolean isDisabled) {
        disableUploadTimeout = isDisabled;
    }


    /**
     * Set the compression setting.
     *
     * @param compression The compression setting
     */
    public void setCompression(String compression) {
        compressionConfig.setCompression(compression);
    }

    /**
     * Get the compression setting.
     *
     * @return The compression setting
     */
    public String getCompression() {
        return compressionConfig.getCompression();
    }

    /**
     * Get the compression level.
     *
     * @return The compression level
     */
    protected int getCompressionLevel() {
        return compressionConfig.getCompressionLevel();
    }


    /**
     * Get the list of user agents that should not use compression.
     *
     * @return The user agents that should not use compression
     */
    public String getNoCompressionUserAgents() {
        return compressionConfig.getNoCompressionUserAgents();
    }

    /**
     * Get the pattern of user agents that should not use compression.
     *
     * @return The pattern of user agents that should not use compression
     */
    protected Pattern getNoCompressionUserAgentsPattern() {
        return compressionConfig.getNoCompressionUserAgentsPattern();
    }

    /**
     * Set the list of user agents that should not use compression.
     *
     * @param noCompressionUserAgents The user agents that should not use compression
     */
    public void setNoCompressionUserAgents(String noCompressionUserAgents) {
        compressionConfig.setNoCompressionUserAgents(noCompressionUserAgents);
    }


    /**
     * Get the MIME types that may be subject to compression.
     *
     * @return The MIME types that may be subject to compression
     */
    public String getCompressibleMimeType() {
        return compressionConfig.getCompressibleMimeType();
    }

    /**
     * Set the MIME types that may be subject to compression.
     *
     * @param valueS The MIME types that may be subject to compression
     */
    public void setCompressibleMimeType(String valueS) {
        compressionConfig.setCompressibleMimeType(valueS);
    }

    /**
     * Get the MIME types that may be subject to compression as an array.
     *
     * @return The MIME types that may be subject to compression
     */
    public String[] getCompressibleMimeTypes() {
        return compressionConfig.getCompressibleMimeTypes();
    }


    /**
     * Get the minimum response size for compression to be applied.
     *
     * @return The minimum response size in bytes
     */
    public int getCompressionMinSize() {
        return compressionConfig.getCompressionMinSize();
    }

    /**
     * Set the minimum response size for compression to be applied.
     *
     * @param compressionMinSize The minimum response size in bytes
     */
    public void setCompressionMinSize(int compressionMinSize) {
        compressionConfig.setCompressionMinSize(compressionMinSize);
    }


    /**
     * Get the content encodings that should not be used.
     *
     * @return The content encodings that should not be used
     */
    public String getNoCompressionEncodings() {
        return compressionConfig.getNoCompressionEncodings();
    }

    /**
     * Set the content encodings that should not be used.
     *
     * @param encodings The content encodings that should not be used
     */
    public void setNoCompressionEncodings(String encodings) {
        compressionConfig.setNoCompressionEncodings(encodings);
    }


    /**
     * Check if compression should be used for the specified request/response.
     *
     * @param request The HTTP request
     * @param response The HTTP response
     * @return {@code true} if compression should be used
     */
    public boolean useCompression(Request request, Response response) {
        return compressionConfig.useCompression(request, response);
    }


    private Pattern restrictedUserAgents = null;

    /**
     * Get the string form of the regular expression that defines the User agents which should be restricted to HTTP/1.0
     * support.
     *
     * @return The regular expression as a String
     */
    public String getRestrictedUserAgents() {
        if (restrictedUserAgents == null) {
            return null;
        } else {
            return restrictedUserAgents.toString();
        }
    }

    /**
     * Get the pattern of user agents that should be restricted to HTTP/1.0.
     *
     * @return The pattern of user agents that should be restricted to HTTP/1.0
     */
    protected Pattern getRestrictedUserAgentsPattern() {
        return restrictedUserAgents;
    }

    /**
     * Set restricted user agent list (which will downgrade the connector to HTTP/1.0 mode). Regular expression as
     * supported by {@link Pattern}.
     *
     * @param restrictedUserAgents The regular expression as supported by {@link Pattern} for the user agents
     */
    public void setRestrictedUserAgents(String restrictedUserAgents) {
        if (restrictedUserAgents == null || restrictedUserAgents.isEmpty()) {
            this.restrictedUserAgents = null;
        } else {
            this.restrictedUserAgents = Pattern.compile(restrictedUserAgents);
        }
    }


    private String server;

    /**
     * Get the value of the Server response header.
     *
     * @return The value of the Server response header
     */
    public String getServer() {
        return server;
    }

    /**
     * Set the server header name.
     *
     * @param server The new value to use for the server header
     */
    public void setServer(String server) {
        this.server = server;
    }


    private boolean serverRemoveAppProvidedValues = false;

    /**
     * Should application provider values for the HTTP Server header be removed. Note that if {@link #server} is set,
     * any application provided value will be over-ridden.
     *
     * @return {@code true} if application provided values should be removed, otherwise {@code false}
     */
    public boolean getServerRemoveAppProvidedValues() {
        return serverRemoveAppProvidedValues;
    }

    /**
     * Set whether application provider values for the HTTP Server header should be removed.
     *
     * @param serverRemoveAppProvidedValues {@code true} if application provided values should be removed
     */
    public void setServerRemoveAppProvidedValues(boolean serverRemoveAppProvidedValues) {
        this.serverRemoveAppProvidedValues = serverRemoveAppProvidedValues;
    }


    /**
     * Maximum size of trailing headers in bytes
     */
    private int maxTrailerSize = 8192;

    /**
     * Get the maximum size of trailing headers.
     *
     * @return The maximum size of trailing headers in bytes
     */
    public int getMaxTrailerSize() {
        return maxTrailerSize;
    }

    /**
     * Set the maximum size of trailing headers.
     *
     * @param maxTrailerSize The maximum size of trailing headers in bytes
     */
    public void setMaxTrailerSize(int maxTrailerSize) {
        this.maxTrailerSize = maxTrailerSize;
    }


    /**
     * Maximum size of extension information in chunked encoding
     */
    private int maxExtensionSize = 8192;

    /**
     * Get the maximum size of extension information in chunked encoding.
     *
     * @return The maximum size of extension information in bytes
     */
    public int getMaxExtensionSize() {
        return maxExtensionSize;
    }

    /**
     * Set the maximum size of extension information in chunked encoding.
     *
     * @param maxExtensionSize The maximum size of extension information in bytes
     */
    public void setMaxExtensionSize(int maxExtensionSize) {
        this.maxExtensionSize = maxExtensionSize;
    }


    /**
     * Maximum amount of request body to swallow.
     */
    private int maxSwallowSize = 2 * 1024 * 1024;

    /**
     * Get the maximum amount of request body to swallow.
     *
     * @return The maximum amount of request body to swallow in bytes
     */
    public int getMaxSwallowSize() {
        return maxSwallowSize;
    }

    /**
     * Set the maximum amount of request body to swallow.
     *
     * @param maxSwallowSize The maximum amount of request body to swallow in bytes
     */
    public void setMaxSwallowSize(int maxSwallowSize) {
        this.maxSwallowSize = maxSwallowSize;
    }


    /**
     * This field indicates if the protocol is treated as if it is secure. This normally means https is being used but
     * can be used to fake https e.g behind a reverse proxy.
     */
    private boolean secure;

    /**
     * Get the flag indicating if this protocol is treated as secure.
     *
     * @return {@code true} if this protocol is treated as secure
     */
    public boolean getSecure() {
        return secure;
    }

    /**
     * Set the flag indicating if this protocol is treated as secure.
     *
     * @param b {@code true} if this protocol is treated as secure
     */
    public void setSecure(boolean b) {
        secure = b;
    }


    /**
     * The names of headers that are allowed to be sent via a trailer when using chunked encoding. They are stored in
     * lower case.
     */
    private final Set<String> allowedTrailerHeaders = ConcurrentHashMap.newKeySet();

    /**
     * Set the names of headers that are allowed to be sent via a trailer when using chunked encoding.
     *
     * @param commaSeparatedHeaders Comma separated list of header names
     */
    public void setAllowedTrailerHeaders(String commaSeparatedHeaders) {
        // Jump through some hoops so we don't end up with an empty set while
        // doing updates.
        Set<String> toRemove = new HashSet<>(allowedTrailerHeaders);
        if (commaSeparatedHeaders != null) {
            String[] headers = commaSeparatedHeaders.split(",");
            for (String header : headers) {
                String trimmedHeader = header.trim().toLowerCase(Locale.ENGLISH);
                if (toRemove.contains(trimmedHeader)) {
                    toRemove.remove(trimmedHeader);
                } else {
                    allowedTrailerHeaders.add(trimmedHeader);
                }
            }
            allowedTrailerHeaders.removeAll(toRemove);
        } else {
            allowedTrailerHeaders.clear();
        }
    }

    /**
     * Get the set of allowed trailer header names.
     *
     * @return The set of allowed trailer header names
     */
    protected Set<String> getAllowedTrailerHeadersInternal() {
        return allowedTrailerHeaders;
    }

    /**
     * Check if a header name is in the set of allowed trailer headers.
     *
     * @param headerName The header name to check
     * @return {@code true} if the header is allowed as a trailer header
     */
    public boolean isTrailerHeaderAllowed(String headerName) {
        return allowedTrailerHeaders.contains(headerName.trim().toLowerCase(Locale.ENGLISH));
    }

    /**
     * Get the list of allowed trailer headers as a comma-separated string.
     *
     * @return The allowed trailer headers as a comma-separated string
     */
    public String getAllowedTrailerHeaders() {
        // Chances of a change during execution of this line are small enough
        // that a sync is unnecessary.
        List<String> copy = new ArrayList<>(allowedTrailerHeaders);
        return StringUtils.join(copy);
    }

    /**
     * Add a header name to the set of allowed trailer headers.
     *
     * @param header The header name to add
     */
    public void addAllowedTrailerHeader(String header) {
        if (header != null) {
            allowedTrailerHeaders.add(header.trim().toLowerCase(Locale.ENGLISH));
        }
    }

    /**
     * Remove a header name from the set of allowed trailer headers.
     *
     * @param header The header name to remove
     */
    public void removeAllowedTrailerHeader(String header) {
        if (header != null) {
            allowedTrailerHeaders.remove(header.trim().toLowerCase(Locale.ENGLISH));
        }
    }


    // ------------------------------------------------ HTTP specific properties
    // ------------------------------------------ passed through to the EndPoint

    /**
     * Check if SSL is enabled on this connector.
     *
     * @return {@code true} if SSL is enabled
     */
    public boolean isSSLEnabled() {
        return getEndpoint().isSSLEnabled();
    }


    /**
     * Check if sendfile is enabled for this connector.
     *
     * @return {@code true} if sendfile is enabled
     */
    public boolean getUseSendfile() {
        return getEndpoint().getUseSendfile();
    }

    /**
     * Set whether sendfile is enabled for this connector.
     *
     * @param useSendfile {@code true} if sendfile is enabled
     */
    public void setUseSendfile(boolean useSendfile) {
        getEndpoint().setUseSendfile(useSendfile);
    }


    /**
     * Get the maximum number of requests which can be performed over a keep-alive connection.
     * The default is the same as for Apache HTTP Server (100).
     *
     * @return The maximum number of requests which can be performed over a keep-alive connection
     */
    public int getMaxKeepAliveRequests() {
        return getEndpoint().getMaxKeepAliveRequests();
    }

    /**
     * Set the maximum number of Keep-Alive requests to allow. This is to safeguard from DoS attacks. Setting to a
     * negative value disables the limit.
     *
     * @param mkar The new maximum number of Keep-Alive requests allowed
     */
    public void setMaxKeepAliveRequests(int mkar) {
        getEndpoint().setMaxKeepAliveRequests(mkar);
    }


    // ------------------------------------------------------------- Common code

    @Override
    protected Processor createProcessor() {
        return new Http11Processor(this, adapter);
    }


}
