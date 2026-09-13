package server;

import api.Endpoints;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.MultipartConfig;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;

import java.io.IOException;
import java.io.InputStream;

/**
 * {@code POST /api/events/upload}: an exercise-3 events file, as the multipart part
 * {@value Endpoints#UPLOAD_PART}. Every event in it joins the market with the uploader as
 * its market maker, or none does: see {@code MarketEngine.addEventsFromFile}.
 */
@WebServlet(name = "upload", urlPatterns = Endpoints.UPLOAD)
@MultipartConfig(maxFileSize = 5_000_000, maxRequestSize = 6_000_000)
public class UploadServlet extends ApiServlet {

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        write(req, resp, (engine, user) -> {
            Part part;
            try {
                part = req.getPart(Endpoints.UPLOAD_PART);
            } catch (ServletException | IllegalStateException e) {
                throw new IllegalArgumentException("Send the events file as a multipart part named '"
                        + Endpoints.UPLOAD_PART + "'.");
            }
            if (part == null) {
                throw new IllegalArgumentException("The request carries no '" + Endpoints.UPLOAD_PART + "' part.");
            }
            try (InputStream xml = part.getInputStream()) {
                return engine.addEventsFromFile(xml, user);
            }
        });
    }
}
