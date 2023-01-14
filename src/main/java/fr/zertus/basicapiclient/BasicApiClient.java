package fr.zertus.basicapiclient;

import com.google.gson.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.*;
import org.apache.http.client.CookieStore;
import org.apache.http.client.HttpClient;
import org.apache.http.client.HttpRequestRetryHandler;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.*;
import org.apache.http.conn.ConnectTimeoutException;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.LaxRedirectStrategy;
import org.apache.http.protocol.HttpContext;
import org.apache.http.ssl.SSLContextBuilder;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.text.MessageFormat;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

@SuperBuilder
public class BasicApiClient {

    public abstract static class BasicApiClientBuilder<C extends BasicApiClient, B extends BasicApiClientBuilder<C, B>> {

        public BasicApiClientBuilder<?, ?> json() {
            return acceptType(ContentType.APPLICATION_JSON.getMimeType())
                    .contentType(ContentType.APPLICATION_JSON.getMimeType());
        }

        public BasicApiClientBuilder<?, ?> jsonResponse() {
            return responseContentType(ContentType.APPLICATION_JSON.getMimeType());
        }

        public BasicApiClientBuilder<?, ?> xml() {
            return acceptType(ContentType.APPLICATION_XML.getMimeType())
                    .contentType(ContentType.APPLICATION_XML.getMimeType());
        }

        public BasicApiClientBuilder<?, ?> soapXml() {
            return acceptType(ContentType.APPLICATION_SOAP_XML.getMimeType())
                    .contentType(ContentType.APPLICATION_SOAP_XML.getMimeType());
        }

    }

    public static final Logger L = Logger.getLogger(BasicApiClient.class.getName());

    @NonNull
    private final String baseUrl;

    @Getter
    @Setter
    private String token;

    /**
     * Charset used to encode request content
     */
    @Builder.Default
    private final Charset contentCharset = StandardCharsets.UTF_8;

    /**
     * Date format used by gson to serialize/deserialize date
     */
    @Builder.Default
    private final String dateFormat = "dd-MM-yyyy";

    /**
     * Header key for token
     */
    @Builder.Default
    private final String tokenNameInHeader = "Authorization";

    /**
     * Used in token authentication header as prefix
     * examples: Bearer / Basic
     */
    @Builder.Default
    private final String tokenPrefix = "Bearer";

    /**
     * HTTP response timeout when contacting the API endpoint.<br>
     * DNS Round robin will make this value less intuitive, as it will
     * be applied to <em>each</em> potential IP, so the actual
     * timeout will be much more than expected - but setting it lower
     * would prevent any connection.<br>
     * HttpClient has a DNS cache, so only the first request will be slow
     * (depending on the TTL of the response).<br />
     * The first call to the API will take (total) roughly 15.1sec, subsequent ones
     * will be around 200ms
     */
    @Builder.Default
    private final int httpConnectionTimeout = 1500;

    @Builder.Default
    private final int httpGETSocketTimeout = 1500;

    @Builder.Default
    private final int httpPOSTSocketTimeout = 3000;

    @Builder.Default
    private final int httpGETMaxRetry = 3;

    @Singular(ignoreNullCollections = true)
    private final Map<String, String> additionalHeaders;

    @Builder.Default
    private final String contentType = ContentType.APPLICATION_JSON.getMimeType();

    @Builder.Default
    private final String responseContentType = null;

    @Builder.Default
    private final String acceptType = ContentType.APPLICATION_JSON.getMimeType();

    /**
     * Tells gson to serialize null object
     */
    @Builder.Default
    private final boolean serializeNulls = false;

    /**
     * If true, will throw a {@link ApiException} if {@link #isError(int)} is true
     */
    @Builder.Default
    private final boolean throwExceptionOnHttpError = false;

    /**
     * If true, redirected request will be copied regardless of the http status (301 vs 307)
     */
    @Builder.Default
    private final boolean redirectAnyway = false;

    /**
     * Customize header of xml body
     */
    @Builder.Default
    private final String xmlHeader = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>";

    /**
     * Disables xml JAXB escaping
     */
    @Builder.Default
    private final boolean xmlDisableEscaping = true;

    /**
     * Add classes to xml marshaller
     */
    @Singular(ignoreNullCollections = true, value = "xmlSeeAlso")
    private final List<Class<?>> xmlSeeAlso;

    @Builder.Default
    private final boolean bypassSsl = false;

    @Builder.Default
    private final CookieStore cookies = null;


    /**
     * Called before sending the request to add information in request
     * @param request http request
     * @param url base url + this = full url
     */
    protected void customizeRequest(HttpRequest request, String url) {
        if (request instanceof HttpRequestBase) {
            String fullUrl = buildUrl(url);
            ((HttpRequestBase) request).setURI(URI.create(fullUrl));
        }
        if (token != null) {
            request.addHeader(tokenNameInHeader, (tokenPrefix != null && !StringUtils.isBlank(tokenPrefix) ? tokenPrefix + " " : "") + token);
        }
        if (additionalHeaders != null) {
            for (Map.Entry<String, String> e : additionalHeaders.entrySet()) {
                request.addHeader(e.getKey(), e.getValue());
            }
        }
        if (request instanceof HttpEntityEnclosingRequest && contentType != null) {
            request.addHeader("Content-Type", contentType);
        }
        if (acceptType != null) {
            request.addHeader("Accept", acceptType);
        }
    }


    /**
     * Send the request with the {@link HttpClient} and log common exceptions
     * @param client the client executing the request
     * @param request the request
     * @param url endpoint without base url
     * @return response provided by url
     * @throws IOException IOExceptions
     */
    protected HttpResponse executeRequest(HttpClient client, HttpUriRequest request, String url) throws IOException {
        customizeRequest(request, url);
        long tick = System.currentTimeMillis();
        try {
            return client.execute(request);
        } catch (ConnectTimeoutException e) {
            long tock = System.currentTimeMillis() - tick;
            L.warning(request.getMethod() + " " + request.getURI().toString() + " timed out after " + tock + "ms. An exception will be thrown");
            throw e;
        } catch (SocketTimeoutException e) {
            long tock = System.currentTimeMillis() - tick;
            L.log(Level.WARNING, request.getMethod() + " " + request.getURI().toString() + " timed out after " + tock + "ms. Another exception will be thrown", e);
            throw new SocketTimeoutException(e.getMessage());
        }
    }

    /**
     * Execute the request with {@link #executeRequest} and parse the response
     * @param request the request
     * @param url endpoint without base url
     * @param type class to parse the response body
     * @param <T> type of response
     * @return the response with the http code
     * @throws IOException IOExceptions
     * @throws ApiException thrown when {@link #throwExceptionOnHttpError} is true and {@link #isError(int)} is true or on {@link ParsingException}
     */
    protected <T> ApiResponse<T> getResponse(HttpUriRequest request, String url, Class<T> type) throws IOException, ApiException {
        try (CloseableHttpClient client = buildHttpClient(request)) {
            HttpResponse response = executeRequest(client, request, url);
            InputStream is = response.getEntity().getContent();
            String content = IOUtils.toString(is, StandardCharsets.UTF_8);
            IOUtils.closeQuietly(is);
            int httpCode = response.getStatusLine().getStatusCode();
            if (throwExceptionOnHttpError && isError(httpCode)) {
                throw new ApiResponseException(request.getURI().toString(), httpCode, content, response);
            }
            T responseContent = stringToObject(content, type);
            return new ApiResponse<>(httpCode, Arrays.asList(response.getAllHeaders()), responseContent);
        }
    }

    /**
     * Execute a get at the provided url and return the parsed response
     * @param url endpoint without base url
     * @param type class to parse the response body
     * @param <T> type of response
     * @return the response with the http code
     * @throws IOException IOExceptions
     * @throws ApiException thrown when {@link #throwExceptionOnHttpError} is true and {@link #isError(int)} is true or on {@link ParsingException}
     */
    public <T> ApiResponse<T> get(String url, Class<T> type) throws IOException, ApiException {
        return getResponse(new HttpGet(), url, type);
    }

    /**
     * Execute a delete at the provided url and return the parsed response
     * @param url endpoint without base url
     * @param type class to parse the response body
     * @param <T> type of response
     * @return the response with the http code
     * @throws IOException IOExceptions
     * @throws ApiException thrown when {@link #throwExceptionOnHttpError} is true and {@link #isError(int)} is true or on {@link ParsingException}
     */
    public <T> ApiResponse<T> delete(String url, Class<T> type) throws IOException, ApiException {
        return getResponse(new HttpDelete(), url, type);
    }

    /**
     * Execute the given request at provided url with provided body
     * @param url endpoint without base url
     * @param type class used to parse the response body
     * @param request the request
     * @param body body of the request
     * @param <T> type of response
     * @return the response with the http code
     * @throws IOException IOExceptions
     * @throws ApiException thrown when {@link #throwExceptionOnHttpError} is true and {@link #isError(int)} is true or on {@link ParsingException}
     */
    protected <T> ApiResponse<T> executeEnclosingRequest(String url, Class<T> type, HttpEntityEnclosingRequestBase request, HttpEntity body) throws IOException, ApiException {
        request.setEntity(body);
        return getResponse(request, url, type);
    }

    /**
     * Execute a post at the provided url with provided body and return the parsed response
     * @param url endpoint without base url
     * @param type class used to parse the response body
     * @param body body of the request
     * @param <T> type of response
     * @return the response with the http code
     * @throws IOException IOExceptions
     * @throws ApiException thrown when {@link #throwExceptionOnHttpError} is true and {@link #isError(int)} is true or on {@link ParsingException}
     */
    public <T> ApiResponse<T> post(String url, Class<T> type, HttpEntity body) throws IOException, ApiException {
        return executeEnclosingRequest(url, type, new HttpPost(), body);
    }

    /**
     * Execute a put at the provided url with provided body and return the parsed response
     * @param url endpoint without base url
     * @param type class used to parse the response body
     * @param body body of the request
     * @param <T> type of response
     * @return the response with the http code
     * @throws IOException IOExceptions
     * @throws ApiException thrown when {@link #throwExceptionOnHttpError} is true and {@link #isError(int)} is true or on {@link ParsingException}
     */
    public <T> ApiResponse<T> put(String url, Class<T> type, HttpEntity body) throws IOException, ApiException {
        return executeEnclosingRequest(url, type, new HttpPut(), body);
    }

    /**
     * Execute the given request at provided url with provided body
     * @param url endpoint without base url
     * @param type class used to parse the response body
     * @param request the request
     * @param content body of the request
     * @param <T> type of response
     * @return the response with the http code
     * @throws IOException IOExceptions
     * @throws ApiException thrown when {@link #throwExceptionOnHttpError} is true and {@link #isError(int)} is true or on {@link ParsingException}
     */
    protected <T> ApiResponse<T> executeEnclosingRequest(String url, Class<T> type, HttpEntityEnclosingRequestBase request, String content) throws IOException, ApiException {
        return executeEnclosingRequest(url, type, request, new StringEntity(content, ContentType.create(contentType, contentCharset)));
    }

    /**
     * Execute a post at the provided url with provided body and return the parsed response
     * @param url endpoint without base url
     * @param type class used to parse the response body
     * @param content content of the request
     * @param <T> type of response
     * @return the response with the http code
     * @throws IOException IOExceptions
     * @throws ApiException thrown when {@link #throwExceptionOnHttpError} is true and {@link #isError(int)} is true or on {@link ParsingException}
     */
    public <T> ApiResponse<T> post(String url, Class<T> type, String content) throws IOException, ApiException {
        return executeEnclosingRequest(url, type, new HttpPost(), content);
    }

    /**
     * Execute a put at the provided url with provided body and return the parsed response
     * @param url endpoint without base url
     * @param type class used to parse the response body
     * @param content content of the request
     * @param <T> type of response
     * @return the response with the http code
     * @throws IOException IOExceptions
     * @throws ApiException thrown when {@link #throwExceptionOnHttpError} is true and {@link #isError(int)} is true or on {@link ParsingException}
     */
    public <T> ApiResponse<T> put(String url, Class<T> type, String content) throws IOException, ApiException {
        return executeEnclosingRequest(url, type, new HttpPut(), content);
    }

    /**
     * Execute the given request at provided url with provided body
     * @param url endpoint without base url
     * @param type class used to parse the response body
     * @param request the request
     * @param content body of the request
     * @param <T> type of response
     * @return the response with the http code
     * @throws IOException IOExceptions
     * @throws ApiException thrown when {@link #throwExceptionOnHttpError} is true and {@link #isError(int)} is true or on {@link ParsingException}
     * @throws ParsingException thrown when content cant be parsed to string
     */
    protected <T> ApiResponse<T> executeEnclosingRequest(String url, Class<T> type, HttpEntityEnclosingRequestBase request, Object content) throws IOException, ApiException {
        String entityContent = objectToString(content);
        return executeEnclosingRequest(url, type, request, entityContent);
    }

    /**
     * Execute a post at the provided url with json object and return the parsed response
     * @param url endpoint without base url
     * @param type class used to parse the response body
     * @param content the json object used in the body
     * @param <T> type of response
     * @return the response with the http code
     * @throws IOException IOExceptions
     * @throws ApiException thrown when {@link #throwExceptionOnHttpError} is true and {@link #isError(int)} is true or on {@link ParsingException}
     * @throws ParsingException thrown when content cant be parsed to string
     */
    public <T> ApiResponse<T> post(String url, Class<T> type, Object content) throws IOException, ApiException {
        return executeEnclosingRequest(url, type, new HttpPost(), content);
    }

    /**
     * Execute a put at the provided url with json object and return the parsed response
     * @param url endpoint without base url
     * @param type class used to parse the response body
     * @param content the json object used in the body
     * @param <T> type of response
     * @return the response with the http code
     * @throws IOException IOExceptions
     * @throws ApiException thrown when {@link #throwExceptionOnHttpError} is true and {@link #isError(int)} is true or on {@link ParsingException}
     * @throws ParsingException thrown when content cant be parsed to string
     */
    public <T> ApiResponse<T> put(String url, Class<T> type, Object content) throws IOException, ApiException {
        return executeEnclosingRequest(url, type, new HttpPut(), content);
    }

    // Add methods for PATCH requests
    /**
     * Execute a patch at the provided url with provided body and return the parsed response
     * @param url endpoint without base url
     * @param type class used to parse the response body
     * @param body body of the request
     * @param <T> type of response
     * @return the response with the http code
     * @throws IOException IOExceptions
     * @throws ApiException thrown when {@link #throwExceptionOnHttpError} is true and {@link #isError(int)} is true or on {@link ParsingException}
     */
    public <T> ApiResponse<T> patch(String url, Class<T> type, HttpEntity body) throws IOException, ApiException {
        return executeEnclosingRequest(url, type, new HttpPatch(), body);
    }

    /**
     * Execute a patch at the provided url with provided body and return the parsed response
     * @param url endpoint without base url
     * @param type class used to parse the response body
     * @param content content of the request
     * @param <T> type of response
     * @return the response with the http code
     * @throws IOException IOExceptions
     * @throws ApiException thrown when {@link #throwExceptionOnHttpError} is true and {@link #isError(int)} is true or on {@link ParsingException}
     */
    public <T> ApiResponse<T> patch(String url, Class<T> type, String content) throws IOException, ApiException {
        return executeEnclosingRequest(url, type, new HttpPatch(), content);
    }

    /**
     * Execute a patch at the provided url with json object and return the parsed response
     * @param url endpoint without base url
     * @param type class used to parse the response body
     * @param content the json object used in the body
     * @param <T> type of response
     * @return the response with the http code
     * @throws IOException IOExceptions
     * @throws ApiException thrown when {@link #throwExceptionOnHttpError} is true and {@link #isError(int)} is true or on {@link ParsingException}
     * @throws ParsingException thrown when content can't be parsed to string
     */
    public <T> ApiResponse<T> patch(String url, Class<T> type, Object content) throws IOException, ApiException {
        return executeEnclosingRequest(url, type, new HttpPatch(), content);
    }

    /**
     * Execute a post at the provided url with url parameters and return the parsed response
     * @param url endpoint without base url
     * @param type class used to parse the response body
     * @param urlParameters url parameters
     * @param <T> type of response
     * @return the response with the http code
     * @throws IOException IOExceptions
     * @throws ApiException thrown when {@link #throwExceptionOnHttpError} is true and {@link #isError(int)} is true or on {@link ParsingException}
     */
    public <T> ApiResponse<T> postUrlFormEncoded(String url, Class<T> type, List<? extends NameValuePair> urlParameters) throws IOException, ApiException {
        return post(url, type, new UrlEncodedFormEntity(urlParameters, contentCharset));
    }

    /**
     * Execute a put at the provided url with url parameters and return the parsed response
     * @param url endpoint without base url
     * @param type class used to parse the response body
     * @param urlParameters url parameters
     * @param <T> type of response
     * @return the response with the http code
     * @throws IOException IOExceptions
     * @throws ApiException thrown when {@link #throwExceptionOnHttpError} is true and {@link #isError(int)} is true or on {@link ParsingException}
     */
    public <T> ApiResponse<T> putUrlFormEncoded(String url, Class<T> type, List<? extends NameValuePair> urlParameters) throws IOException, ApiException {
        return post(url, type, new UrlEncodedFormEntity(urlParameters, contentCharset));
    }

    /**
     * Execute a patch at the provided url with url parameters and return the parsed response
     * @param url endpoint without base url
     * @param type class used to parse the response body
     * @param urlParameters url parameters
     * @param <T> type of response
     * @return the response with the http code
     * @throws IOException IOExceptions
     * @throws ApiException thrown when {@link #throwExceptionOnHttpError} is true and {@link #isError(int)} is true or on {@link ParsingException}
     */
    public <T> ApiResponse<T> patchUrlFormEncoded(String url, Class<T> type, List<? extends NameValuePair> urlParameters) throws IOException, ApiException {
        return patch(url, type, new UrlEncodedFormEntity(urlParameters, contentCharset));
    }


    private String buildUrl(String url) {
        if (StringUtils.isBlank(url)) {
            return baseUrl;
        } else if (url.startsWith("/")) {
            return baseUrl + url;
        }
        return url;
    }

    public boolean isError(int httpStatusCode){
        return httpStatusCode < HttpStatus.SC_OK
                || httpStatusCode >= HttpStatus.SC_OK + 100;
    }

    private boolean isJson() {
        return ContentType.APPLICATION_JSON.getMimeType().equals(contentType);
    }

    private boolean isJsonResponse() {
        return responseContentType != null && ContentType.APPLICATION_JSON.getMimeType().equals(responseContentType);
    }

    private boolean isXml() {
        return contentType != null && contentType.contains("xml");
    }

    public Gson buildGson() {
        GsonBuilder builder = new GsonBuilder()
                .setDateFormat(dateFormat);
        if (serializeNulls) {
            builder.serializeNulls();
        }
        return builder.create();
    }

    private Class<?>[] getMarshallerClasses(Class<?> type) {
        Class<?>[] result = new Class<?>[xmlSeeAlso.size() + 1];
        result[0] = type;
        System.arraycopy(xmlSeeAlso.toArray(new Class<?>[0]), 0, result, 1, xmlSeeAlso.size());
        return result;
    }

    public Marshaller buildMarshaller(Class<?> type) throws JAXBException {
        JAXBContext c = JAXBContext.newInstance(getMarshallerClasses(type));
        Marshaller m = c.createMarshaller();
        //m.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
        m.setProperty(Marshaller.JAXB_FRAGMENT, true);
        if (xmlHeader != null) {
            m.setProperty("com.sun.xml.bind.xmlHeaders", xmlHeader);
        }
        return m;
    }

    public Unmarshaller buildUnmarshaller(Class<?> type) throws JAXBException {
        JAXBContext c = JAXBContext.newInstance(getMarshallerClasses(type));
        return c.createUnmarshaller();
    }

    private String objectToString(Object object) throws IOException, ParsingException {
        try {
            if (object instanceof String) {
                return (String) object;
            } else if (isJson()) {
                return buildGson().toJson(object);
            } else if (isXml()) {
                try (StringWriter sw = new StringWriter()) {
                    buildMarshaller(object.getClass()).marshal(object, sw);
                    return sw.toString();
                }
            } else {
                return object.toString();
            }
        } catch (JsonSyntaxException | JAXBException e) {
            throw new ParsingException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T stringToObject(String string, Class<T> type) throws ParsingException {
        try {
            if (isJsonResponse()) {
                return parseJson(string, type);
            } else if (String.class.isAssignableFrom(type)) {
                return (T) string;
            } else if (isJson()) {
                return parseJson(string, type);
            } else if (isXml()) {
                return (T) buildUnmarshaller(type).unmarshal(new StringReader(string));
            }
            return null;
        } catch (JsonSyntaxException | JAXBException e) {
            throw new ParsingException(e);
        }
    }

    private <T> T parseJson(String string, Class<T> type) {
        if (JsonObject.class.isAssignableFrom(type)) {
            JsonElement el = JsonParser.parseString(string);
            if (el.isJsonObject()) {
                return (T) el;
            } else {
                throw new JsonSyntaxException("Return response cannot be parsed to a JsonObject");
            }
        }
        return buildGson().fromJson(string, type);
    }

    private CloseableHttpClient buildHttpClient(HttpUriRequest httpRequest) {
        HttpClientBuilder builder = HttpClientBuilder.create();
        RequestConfig.Builder requestBuilder = RequestConfig.custom()
                .setConnectTimeout(httpConnectionTimeout);

        if (httpRequest.getMethod().equals(HttpGet.METHOD_NAME)) {
            HttpRequestRetryHandler retryHandler = (exception, executionCount, context) -> {
                if (executionCount >= httpGETMaxRetry) {
                    // Do not retry if over max retry count
                    return false;
                }
                if (exception instanceof UnknownHostException) {
                    // Unknown host
                    return false;
                }
                if (exception instanceof ConnectTimeoutException) {
                    // Connection refused
                    return false;
                }
                if (exception instanceof SSLException) {
                    // SSL handshake exception
                    return false;
                }
                // will retry if Timeout
                HttpUriRequest request = (HttpUriRequest) context.getAttribute("http.request");
                boolean idempotent = !(request instanceof HttpEntityEnclosingRequest);
                // Retry if the request is considered idempotent
                if (idempotent) {
                    L.warning(MessageFormat.format("GET {0} will retry (retry number {1}/{2}) after following I/O Exception ({3}) was caught : {4}",
                            request.getURI(), executionCount, httpGETMaxRetry, exception.getClass().getName(), exception.getMessage()));
                }
                return idempotent;
            };
            builder.setRetryHandler(retryHandler);
            requestBuilder.setSocketTimeout(httpGETSocketTimeout);
        } else {
            requestBuilder.setSocketTimeout(httpPOSTSocketTimeout);
        }
        requestBuilder.setRedirectsEnabled(true);
        if (redirectAnyway) {
            builder.setRedirectStrategy(new LaxRedirectStrategy() {
                @Override
                public HttpUriRequest getRedirect(HttpRequest request, HttpResponse response, HttpContext context) throws ProtocolException {
                    final URI uri = getLocationURI(request, response, context);
                    final String method = request.getRequestLine().getMethod();
                    if (method.equalsIgnoreCase(HttpHead.METHOD_NAME)) {
                        return new HttpHead(uri);
                    } else if (method.equalsIgnoreCase(HttpGet.METHOD_NAME)) {
                        return new HttpGet(uri);
                    } else {
                        return RequestBuilder.copy(request).setUri(uri).build();
                    }
                }
            });
        }
        if (bypassSsl) {
            try {
                SSLContext sslContext = new SSLContextBuilder().loadTrustMaterial(null, (certificate, authType) -> true).build();
                builder.setSSLContext(sslContext);
                builder.setSSLHostnameVerifier(NoopHostnameVerifier.INSTANCE);
            } catch (NoSuchAlgorithmException | KeyManagementException | KeyStoreException e) {
                L.warning("Cannot bypass SSL : " + e.getMessage());
            }
        }
        if (cookies != null) {
            builder.setDefaultCookieStore(cookies);
        }
        return builder.setDefaultRequestConfig(requestBuilder.build()).build();
    }

}
