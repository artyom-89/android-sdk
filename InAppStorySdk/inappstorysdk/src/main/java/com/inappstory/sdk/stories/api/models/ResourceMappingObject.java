package com.inappstory.sdk.stories.api.models;


import com.inappstory.sdk.network.SerializedName;

/**
 * Created by paperrose on 19.02.2018.
 */

public class ResourceMappingObject {
    @SerializedName("url")
    String url;
    @SerializedName("key")
    String key;
    @SerializedName("type")
    String type;

    @SerializedName("slide_index")
    Integer index;

    public String getType() {
        return type;
    }
    public String getUrl() {
        return url;
    }
    public Integer getIndex() {
        return index;
    }

    public String getKey() {
        return key;
    }
}