// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.preview;

import com.zextras.carbonio.preview.exceptions.BadRequest;
import com.zextras.carbonio.preview.exceptions.InternalServerError;
import com.zextras.carbonio.preview.exceptions.ItemNotFound;
import com.zextras.carbonio.preview.exceptions.ValidationError;
import com.zextras.carbonio.preview.queries.BlobResponse;
import com.zextras.carbonio.preview.queries.Query;
import io.vavr.control.Try;

import java.io.IOException;
import java.io.InputStream;
import java.text.MessageFormat;

import org.apache.http.HttpEntity;
import org.apache.http.HttpStatus;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;

public class PreviewClient {

  private final String previewEndpoint;
  private final String previewUrl;
  private final String imageEndpoint       = "image";
  private final String pdfEndpoint         = "pdf";
  private final String documentEndpoint    = "document";
  private final String healthReadyEndpoint = "/health/ready/";
  private final String thumbnailPathParam  = "thumbnail";
  private final String fileOwnerIdHeader   = "FileOwnerId";

  // UTILITY

  PreviewClient(String previewURL) {
    this.previewUrl = previewURL;
    this.previewEndpoint = previewURL + "/preview";
  }

  public static PreviewClient atURL(String url) {
    return new PreviewClient(url);
  }

  public static PreviewClient atURL(
    String protocol,
    String domain,
    Integer port
  ) {
    return new PreviewClient(protocol + "://" + domain + ":" + port);
  }

  private String createPathForThumbnail(Query query) {
    String toModifyRequestUri = query.toString();
    // 1 because an empty query contains '/'
    if (toModifyRequestUri.chars().filter(ch -> ch == '/').count() > 1) {
      int index = toModifyRequestUri.indexOf('?');
      if (index == -1) {
        return toModifyRequestUri + "/" + thumbnailPathParam + "/";
      } else {
        return toModifyRequestUri.substring(0, index - 1) + "/" + thumbnailPathParam
          + toModifyRequestUri.substring(index - 1);
      }
    } else {
      return "/" + thumbnailPathParam + "/";
    }
  }

  // IMAGE

  public Try<BlobResponse> getPreviewOfImage(Query query) {
    return sendGetToPreviewService(query.toString(), imageEndpoint, query.getFileOwnerId().get());
  }


  public Try<BlobResponse> getThumbnailOfImage(Query query) {
    return sendGetToPreviewService(
      createPathForThumbnail(query), imageEndpoint, query.getFileOwnerId().get()
    );
  }


  public Try<BlobResponse> postPreviewOfImage(
    InputStream blob,
    Query query,
    String fileName
  ) {
    return sendPostToPreviewService(blob, fileName, query.toString(), imageEndpoint);
  }

  public Try<BlobResponse> postThumbnailOfImage(
    InputStream blob,
    Query query,
    String fileName
  ) {
    return sendPostToPreviewService(blob, fileName, createPathForThumbnail(query), imageEndpoint);
  }

  //PDF

  public Try<BlobResponse> getPreviewOfPdf(Query query) {
    return sendGetToPreviewService(query.toString(), pdfEndpoint, query.getFileOwnerId().get());
  }


  public Try<BlobResponse> getThumbnailOfPdf(Query query) {
    return sendGetToPreviewService(
      createPathForThumbnail(query), pdfEndpoint, query.getFileOwnerId().get()
    );
  }

  public Try<BlobResponse> postThumbnailOfPdf(
    InputStream blob,
    Query query,
    String fileName
  ) {
    return sendPostToPreviewService(blob, fileName, createPathForThumbnail(query), pdfEndpoint);
  }

  public Try<BlobResponse> postPreviewOfPdf(
    InputStream blob,
    Query query,
    String fileName
  ) {
    return sendPostToPreviewService(blob, fileName, query.toString(), pdfEndpoint);
  }

  //DOCUMENT

  public Try<BlobResponse> getPreviewOfDocument(Query query) {
    return sendGetToPreviewService(
      query.toString(), documentEndpoint, query.getFileOwnerId().get()
    );
  }


  public Try<BlobResponse> getThumbnailOfDocument(Query query) {
    return sendGetToPreviewService(
      createPathForThumbnail(query), documentEndpoint, query.getFileOwnerId().get()
    );
  }

  public Try<BlobResponse> postThumbnailOfDocument(
    InputStream blob,
    Query query,
    String fileName
  ) {
    return sendPostToPreviewService(
      blob, fileName, createPathForThumbnail(query), documentEndpoint
    );
  }

  public Try<BlobResponse> postPreviewOfDocument(
    InputStream blob,
    Query query,
    String fileName
  ) {
    return sendPostToPreviewService(blob, fileName, query.toString(), documentEndpoint);
  }

  // API CALL

  private Try<BlobResponse> sendPostToPreviewService(
    InputStream blob,
    String fileName,
    String query,
    String endpoint
  ) {
    String requestUri = MessageFormat.format(
      "{0}/{1}{2}",
      previewEndpoint, endpoint, query
    );
    HttpPost httpPost = new HttpPost(requestUri);

    MultipartEntityBuilder builder = MultipartEntityBuilder.create();
    builder.addBinaryBody("file", blob, ContentType.APPLICATION_OCTET_STREAM, fileName);
    HttpEntity multipart = builder.build();
    httpPost.setEntity(multipart);
    return sendRequestToPreviewService(httpPost);

  }

  private Try<BlobResponse> sendGetToPreviewService(
    String query,
    String endpoint,
    String accountHeaderValue
  ) {
    String requestUri = MessageFormat.format(
      "{0}/{1}{2}",
      previewEndpoint, endpoint, query
    );
    HttpGet request = new HttpGet(requestUri);
    request.setHeader(fileOwnerIdHeader, accountHeaderValue);
    return sendRequestToPreviewService(request);
  }

  private Try<BlobResponse> sendRequestToPreviewService(HttpRequestBase request) {

    CloseableHttpClient httpClient = HttpClients.createMinimal();
    try {
      CloseableHttpResponse response = httpClient.execute(request);
      int statusCode = response.getStatusLine().getStatusCode();
      switch (statusCode) {
        case HttpStatus.SC_OK:
          return Try.success(new BlobResponse(response.getEntity()));
        case HttpStatus.SC_NOT_FOUND:
          return Try.failure(new ItemNotFound());
        case HttpStatus.SC_UNPROCESSABLE_ENTITY:
          return Try.failure(new ValidationError());
        case HttpStatus.SC_BAD_REQUEST:
          return Try.failure(new BadRequest());
        default:
          return Try.failure(new InternalServerError());
      }
    } catch (IOException exception) {
      return Try.failure(new InternalServerError(exception));
    }
  }


  public boolean healthReady() {
    CloseableHttpClient httpClient = HttpClients.createMinimal();

    String requestUri = MessageFormat.format(
      "{0}{1}",
      previewUrl, healthReadyEndpoint
    );
    HttpGet request = new HttpGet(requestUri);

    try {
      CloseableHttpResponse response = httpClient.execute(request);
      return response.getStatusLine().getStatusCode() == HttpStatus.SC_OK;
    } catch (IOException exception) {
      return false;
    }
  }
}
