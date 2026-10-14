package club.badernet.nestwerk.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.support.RequestContextUtils;

import java.net.URI;

/** Zu große Uploads: zurück zur Seite mit verständlicher Meldung statt Fehlerseite. */
@ControllerAdvice
public class UploadErrorAdvice {

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    String tooLarge(HttpServletRequest request) {
        RequestContextUtils.getOutputFlashMap(request)
                .put("error", "Die Dateien sind zu groß. Eine Datei darf höchstens 20 MB haben.");
        String referer = request.getHeader("Referer");
        String back = "/";
        if (referer != null) {
            try {
                URI uri = URI.create(referer);
                back = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
            } catch (IllegalArgumentException ignored) {
                // ungültiger Referer: zur Startseite
            }
        }
        return "redirect:" + TodoController.safeBack(back, "/");
    }
}
