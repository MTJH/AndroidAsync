package com.koushikdutta.async.http;

import junit.framework.Assert;

import com.koushikdutta.async.ByteBufferList;
import com.koushikdutta.async.DataEmitter;
import com.koushikdutta.async.FilteredDataCallback;
import com.koushikdutta.async.callback.CompletedCallback;
import com.koushikdutta.async.callback.DataCallback;
import com.koushikdutta.async.http.filter.ChunkedInputFilter;
import com.koushikdutta.async.http.filter.GZIPInputFilter;
import com.koushikdutta.async.http.filter.InflaterInputFilter;
import com.koushikdutta.async.http.libcore.RawHeaders;
import com.koushikdutta.async.http.server.UnknownRequestBody;

public class Util {
    public static AsyncHttpRequestBody getBody(RawHeaders headers) {
        String contentType = headers.get("Content-Type");
        if (contentType != null) {
            String[] values = contentType.split(";");
            for (int i = 0; i < values.length; i++) {
                values[i] = values[i].trim();
            }
            for (String ct: values) {
                if (UrlEncodedFormBody.CONTENT_TYPE.equals(ct))
                    return new UrlEncodedFormBody();
                if (MultipartFormDataBody.CONTENT_TYPE.equals(ct))
                    return new MultipartFormDataBody(contentType, values);
            }
        }
        return new UnknownRequestBody(contentType);
    }
    
    public static DataCallback getBodyDecoder(DataCallback callback, RawHeaders headers, boolean server, final CompletedCallback reporter) {
        if ("gzip".equals(headers.get("Content-Encoding"))) {
            GZIPInputFilter gunzipper = new GZIPInputFilter();
            gunzipper.setDataCallback(callback);
            gunzipper.setEndCallback(reporter);
            callback = gunzipper;
        }        
        else if ("deflate".equals(headers.get("Content-Encoding"))) {
            InflaterInputFilter inflater = new InflaterInputFilter();
            inflater.setEndCallback(reporter);
            inflater.setDataCallback(callback);
            callback = inflater;
        }

        int _contentLength;
        try {
            _contentLength = Integer.parseInt(headers.get("Content-Length"));
        }
        catch (Exception ex) {
            _contentLength = -1;
        }
        final int contentLength = _contentLength;
        if (-1 != contentLength) {
            if (contentLength < 0) {
                reporter.onCompleted(new Exception("not using chunked encoding, and no content-length found."));
                return callback;
            }
            if (contentLength == 0) {
                reporter.onCompleted(null);
                return callback;
            }
//            System.out.println("Content len: " + contentLength);
            FilteredDataCallback contentLengthWatcher = new FilteredDataCallback() {
                int totalRead = 0;
                @Override
                public void onDataAvailable(DataEmitter emitter, ByteBufferList bb) {
                    Assert.assertTrue(totalRead < contentLength);
                    ByteBufferList list = bb.get(Math.min(contentLength - totalRead, bb.remaining()));
                    totalRead += list.remaining();
                    super.onDataAvailable(emitter, list);
                    if (totalRead == contentLength)
                        report(null);
                }
            };
            contentLengthWatcher.setDataCallback(callback);
            contentLengthWatcher.setEndCallback(reporter);
            callback = contentLengthWatcher;
        }
        else if ("chunked".equalsIgnoreCase(headers.get("Transfer-Encoding"))) {
            ChunkedInputFilter chunker = new ChunkedInputFilter();
            
            chunker.setEndCallback(reporter);
            chunker.setDataCallback(callback);
            callback = chunker;
        }
        else if (server) {
            // if this is the server, and the client has not indicated a request body, the client is done
            reporter.onCompleted(null);
        }
        // conversely, if this is the client, and the server has not indicated a request body, we do not report
        // the close/end event until the server actually closes the connection.
        return callback;
    }
}
