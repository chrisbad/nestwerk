package club.badernet.nestwerk.web;

import club.badernet.nestwerk.attachment.Attachment;
import club.badernet.nestwerk.attachment.AttachmentService;
import club.badernet.nestwerk.attachment.AttachmentType;
import club.badernet.nestwerk.security.CurrentUser;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Controller
@RequestMapping("/attachments")
public class AttachmentController {

    private final AttachmentService attachments;
    private final CurrentUser currentUser;

    public AttachmentController(AttachmentService attachments, CurrentUser currentUser) {
        this.attachments = attachments;
        this.currentUser = currentUser;
    }

    /** Liefert die Datei mit festem, geprüftem Typ; nur Bilder und PDF werden im Browser angezeigt. */
    @GetMapping("/{id}")
    ResponseEntity<Resource> download(@PathVariable Long id) {
        AttachmentService.Download download = attachments.load(id, currentUser.require());
        Attachment a = download.attachment();
        ContentDisposition disposition = (AttachmentType.inline(a.getContentType())
                ? ContentDisposition.inline() : ContentDisposition.attachment())
                .filename(a.getFilename(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(a.getContentType()))
                .contentLength(a.getSize())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; img-src 'self'; style-src 'unsafe-inline'; sandbox")
                .cacheControl(org.springframework.http.CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
                .body(download.resource());
    }

    @PostMapping("/{id}/delete")
    String delete(@PathVariable Long id, @RequestParam(required = false) String back, RedirectAttributes redirect) {
        attachments.delete(id, currentUser.require());
        redirect.addFlashAttribute("success", "Anhang wurde gelöscht.");
        return "redirect:" + SafeRedirect.target(back, "/");
    }
}
