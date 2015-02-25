package jerluc.me.semantic_beacon;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.ImageRequest;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.Volley;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Class for resolving url metadata.
 * Sends requests to the metadata server
 * which then scrapes the page at the given url
 * for its metadata
 */

class MetadataResolver {
    private static final String TAG = "MetadataResolver";
    private static RequestQueue mRequestQueue;
    private static boolean mIsInitialized = false;
    private static MetadataResolverCallback mMetadataResolverCallback;
    private static Context mContext;

    private static void initialize(Context context) {
        if (mRequestQueue == null) {
            mRequestQueue = Volley.newRequestQueue(context);
        }
        mIsInitialized = true;
        mContext = context;
    }


    /////////////////////////////////
    // callbacks
    /////////////////////////////////

    public interface MetadataResolverCallback {
        public void onUrlMetadataReceived(String url, UrlMetadata urlMetadata);
        public void onUrlMetadataIconReceived();
    }


    /////////////////////////////////
    // utilities
    /////////////////////////////////

    public static void findUrlMetadata(Context context,
                                       MetadataResolverCallback metadataResolverCallback,
                                       String url,
                                       int txPower,
                                       int rssi) {
        // Store the callback so we can call it back later
        mMetadataResolverCallback = metadataResolverCallback;
        initialize(context);
        requestUrlMetadata(url, txPower, rssi);
    }

    /**
     * Start the process that will ask
     * the metadata server for the given url's metadata
     *
     * @param url The url for which to request data
     */
    private static void requestUrlMetadata(String url, int txPower, int rssi) {
        if (!mIsInitialized) {
            Log.e(TAG, "Not initialized.");
            return;
        }

        JsonObjectRequest jsObjRequest = createUrlMetadataRequest(url);
        // Queue the request
        mRequestQueue.add(jsObjRequest);
    }


    private static JsonObjectRequest createUrlMetadataRequest(final String url) {
        return new JsonObjectRequest(url,
                null,
                new Response.Listener<JSONObject>() {
                    // Called when the server returns a response
                    @Override
                    public void onResponse(JSONObject jsonResponse) {
                        try {
                            UrlMetadata metadata = new UrlMetadata();
                            metadata.type = jsonResponse.getString("@type");
                            metadata.name = jsonResponse.getString("name");
                            metadata.siteUrl = jsonResponse.getString("url");
                            metadata.imageUrl = jsonResponse.getString("image");
                            metadata.potentialActions = jsonResponse.getJSONArray("potentialAction");
                            mMetadataResolverCallback.onUrlMetadataReceived(url, metadata);
                            downloadIcon(metadata);
                        } catch (Exception ex) {
                            Log.e(TAG, "Oh noesss!", ex);
                        }
                    }
                },
                new Response.ErrorListener() {
                    @Override
                    public void onErrorResponse(VolleyError volleyError) {
                        Log.i(TAG, "VolleyError: " + volleyError.toString());
                    }
                }
        );
    }

    /**
     * Asynchronously download the image for the url favicon.
     *
     * @param urlMetadata The metadata for the given url
     */
    private static void downloadIcon(final UrlMetadata urlMetadata) {
        ImageRequest imageRequest = new ImageRequest(urlMetadata.imageUrl, new Response.Listener<Bitmap>() {
            @Override
            public void onResponse(Bitmap response) {
                urlMetadata.image = response;
                mMetadataResolverCallback.onUrlMetadataIconReceived();
            }
        }, 0, 0, null, null);
        mRequestQueue.add(imageRequest);
    }

    /**
     * A container class for a url's fetched metadata.
     * The metadata consists of the title, site url, description,
     * iconUrl and the icon (or favicon).
     * This data is scraped via a server that receives a url
     * and returns a json blob.
     */
    public static class UrlMetadata {
        public String type;
        public String name;
        public String siteUrl;
        public String imageUrl;
        public Bitmap image;
        public JSONArray potentialActions;

        public UrlMetadata() {
        }

    }
}
