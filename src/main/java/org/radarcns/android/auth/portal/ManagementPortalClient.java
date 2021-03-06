package org.radarcns.android.auth.portal;

import android.support.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;
import org.radarcns.android.auth.AppAuthState;
import org.radarcns.android.auth.AppSource;
import org.radarcns.android.auth.AuthStringParser;
import org.radarcns.android.util.ResponseHandler;
import org.radarcns.config.ServerConfig;
import org.radarcns.producer.AuthenticationException;
import org.radarcns.producer.rest.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.Map;

import okhttp3.Credentials;
import okhttp3.FormBody;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class ManagementPortalClient implements Closeable {
    private static final Logger logger = LoggerFactory.getLogger(ManagementPortalClient.class);

    public static final String SOURCES_PROPERTY =
            ManagementPortalClient.class.getName() + ".sources";
    public static final String MP_REFRESH_TOKEN_PROPERTY = ManagementPortalClient.class.getName() + ".refreshToken";
    private static final String APPLICATION_JSON = "application/json";
    private static final String APPLICATION_JSON_UTF8 = APPLICATION_JSON + "; charset=utf-8";
    private static final MediaType APPLICATION_JSON_MEDIA_TYPE = MediaType
            .parse(APPLICATION_JSON_UTF8);

    private final RestClient client;

    public ManagementPortalClient(ServerConfig managementPortal) {
        client = new RestClient(managementPortal);
    }

    /**
     * Get subject information from the Management portal. This includes project ID, available
     * source types and assigned sources.
     * @param state current authentication state
     * @throws IOException if the management portal could not be reached or it gave an erroneous
     *                     response.
     */
    public AppAuthState getSubject(AppAuthState state, AuthStringParser parser) throws IOException {
        Request request = client.requestBuilder("api/subjects/" + state.getUserId())
                .headers(state.getOkHttpHeaders())
                .header("Accept", APPLICATION_JSON)
                .build();

        logger.info("Requesting subject {} with headers {}", state.getUserId(),
                state.getOkHttpHeaders());

        return ResponseHandler.handle(client.request(request), parser);
    }

    /** Register a source with the Management Portal. */
    public AppSource registerSource(AppAuthState auth, AppSource source)
            throws IOException, JSONException {
        RequestBody body = RequestBody.create(APPLICATION_JSON_MEDIA_TYPE,
                sourceRegistrationBody(source).toString());

        Request request = client.requestBuilder(
                "api/subjects/" + auth.getUserId() + "/sources")
                .post(body)
                .headers(auth.getOkHttpHeaders())
                .header("Content-Type", APPLICATION_JSON_UTF8)
                .header("Accept", APPLICATION_JSON)
                .build();

        try (Response response = client.request(request)) {
            if (response.code() == 409) {
                throw new ConflictException();
            } else if (response.code() == 404) {
                throw new IOException("User " + auth.getUserId() + " is no longer registered with the ManagementPortal.");
            } else if (response.code() == 401) {
                throw new AuthenticationException("Authentication failure with the ManagementPortal.");
            }

            String responseBody = RestClient.responseBody(response);

            if (!response.isSuccessful()) {
                if (responseBody != null) {
                    throw new IOException(
                            "Cannot complete source registration with the ManagementPortal: "
                                    + responseBody);
                } else {
                    throw new IOException("Cannot complete source registration with the ManagementPortal.");
                }
            } else if (responseBody == null || responseBody.isEmpty()) {
                throw new IOException("Source registration with the ManagementPortal did not yield result.");
            } else {
                parseSourceRegistration(responseBody, source);
                return source;
            }
        }
    }

    static JSONObject sourceRegistrationBody(AppSource source) throws JSONException {
        JSONObject requestBody = new JSONObject();
        // TODO: in a regression from MP 0.2.0 -> 0.2.1 this was removed
//        if (source.getSourceName() != null) {
//            requestBody.put("sourceName", source.getSourceName());
//        }
        requestBody.put("sourceTypeId", source.getSourceTypeId());
        Map<String, String> sourceAttributes = source.getAttributes();
        if (!sourceAttributes.isEmpty()) {
            JSONObject attrs = new JSONObject();
            for (Map.Entry<String, String> attr : sourceAttributes.entrySet()) {
                attrs.put(attr.getKey(), attr.getValue());
            }
            requestBody.put("attributes", attrs);
        }
        return requestBody;
    }

    /**
     * Parse the response of a subject/source registration.
     * @param body registration response body
     * @param source existing source to update with server information.
     * @throws JSONException if the provided body is not valid JSON with the correct properties
     */
    static void parseSourceRegistration(@NonNull String body, @NonNull AppSource source)
            throws JSONException {
        JSONObject responseObject = new JSONObject(body);
        source.setSourceId(responseObject.getString("sourceId"));
        source.setSourceName(responseObject.getString("sourceName"));
        source.setExpectedSourceName(responseObject.getString("expectedSourceName"));
        source.setAttributes(GetSubjectParser.attributesToMap(responseObject.optJSONObject("attributes")));
    }

    @Override
    public void close() {
        client.close();
    }

    public AppAuthState refreshToken(AppAuthState authState, String clientId, String clientSecret, AuthStringParser parser) throws IOException, JSONException {
        try {
            String refreshToken = (String)authState.getProperty(MP_REFRESH_TOKEN_PROPERTY);
            if (refreshToken == null) {
                throw new IllegalArgumentException("No refresh token found");
            }

            RequestBody body = new FormBody.Builder()
                    .add("grant_type", "refresh_token")
                    .add("refresh_token", refreshToken)
                    .build();

            Request request = client.requestBuilder("oauth/token")
                    .post(body)
                    .addHeader("Authorization", Credentials.basic(clientId, clientSecret))
                    .build();

            return ResponseHandler.handle(client.request(request), parser);
        } catch (MalformedURLException e) {
            throw new IllegalStateException("Failed to create request from ManagementPortal url", e);
        }
    }

}
