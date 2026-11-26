package com.pulseagent.security;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.IOException;

/** Caps how many body bytes can be read, so chunked requests without Content-Length are bounded too. */
class LimitedBodyRequest extends HttpServletRequestWrapper {

    private final long max;

    LimitedBodyRequest(HttpServletRequest request, long max) {
        super(request);
        this.max = max;
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
        ServletInputStream in = super.getInputStream();
        return new ServletInputStream() {
            private long read;

            @Override public int read() throws IOException {
                int b = in.read();
                if (b >= 0 && ++read > max) throw new IOException("request body too large");
                return b;
            }

            @Override public int read(byte[] buf, int off, int len) throws IOException {
                int n = in.read(buf, off, len);
                if (n > 0 && (read += n) > max) throw new IOException("request body too large");
                return n;
            }

            @Override public boolean isFinished() { return in.isFinished(); }
            @Override public boolean isReady() { return in.isReady(); }
            @Override public void setReadListener(ReadListener l) { in.setReadListener(l); }
        };
    }
}
