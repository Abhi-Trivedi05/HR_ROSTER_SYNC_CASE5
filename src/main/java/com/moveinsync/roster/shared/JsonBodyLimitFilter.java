package com.moveinsync.roster.shared;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** JSON batches are bounded in bytes before Jackson materializes a request tree; CSV has its own streaming cap. */
@Component @Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE+1)
public class JsonBodyLimitFilter extends OncePerRequestFilter {
    private static final long LIMIT=2*1024*1024;
    private final ObjectMapper mapper;
    public JsonBodyLimitFilter(ObjectMapper mapper) { this.mapper=mapper; }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        if (request.getContentType()==null||!request.getContentType().startsWith("application/json")) { chain.doFilter(request,response);return; }
        if (request.getContentLengthLong()>LIMIT) { rejected(response);return; }
        var wrapped=new HttpServletRequestWrapper(request) {
            @Override public ServletInputStream getInputStream() throws IOException {
                var source=super.getInputStream();
                return new ServletInputStream() {
                    long read;
                    @Override public int read() throws IOException { int c=source.read();if(c!=-1&&++read>LIMIT) throw new IOException("JSON_BODY_LIMIT");return c; }
                    @Override public int read(byte[] bytes,int offset,int length) throws IOException { int n=source.read(bytes,offset,(int)Math.min(length,LIMIT-read+1));if(n>0&&(read+=n)>LIMIT)throw new IOException("JSON_BODY_LIMIT");return n; }
                    @Override public boolean isFinished() { return source.isFinished(); }
                    @Override public boolean isReady() { return source.isReady(); }
                    @Override public void setReadListener(ReadListener listener) { source.setReadListener(listener); }
                };
            }
        };
        chain.doFilter(wrapped,response);
    }
    private void rejected(HttpServletResponse response) throws IOException {
        response.setStatus(413);response.setContentType("application/json");mapper.writeValue(response.getOutputStream(),ApiError.of("BODY_LIMIT_EXCEEDED","JSON body exceeds the configured limit"));
    }
}
