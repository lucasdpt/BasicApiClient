package fr.zertus.basicapiclient;

import lombok.AccessLevel;
import lombok.Data;
import lombok.experimental.FieldDefaults;
import org.apache.http.Header;

import java.util.List;

@Data
@FieldDefaults(level= AccessLevel.PRIVATE)
public class ApiResponse<T> {

    final int httpCode;
    final List<Header> headers;
    final T content;

}
