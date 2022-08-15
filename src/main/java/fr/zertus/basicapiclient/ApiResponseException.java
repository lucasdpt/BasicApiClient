package fr.zertus.basicapiclient;

import lombok.AccessLevel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.FieldDefaults;
import org.apache.http.HttpResponse;

@Data
@FieldDefaults(level= AccessLevel.PRIVATE)
@EqualsAndHashCode(callSuper = true)
public class ApiResponseException extends ApiException {

    final String url;
    final int httpCode;
    final String content;
    final HttpResponse response;

    public ApiResponseException(String url, int httpCode, String content, HttpResponse response) {
        this.url = url;
        this.httpCode = httpCode;
        this.content = content;
        this.response = response;
    }

    public ApiResponseException(String url, int httpCode, String content, HttpResponse response, Exception e) {
        super(e);
        this.url = url;
        this.httpCode = httpCode;
        this.content = content;
        this.response = response;
    }


}