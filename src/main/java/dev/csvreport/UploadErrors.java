package dev.csvreport;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
class UploadErrors {
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ReportStore.ReportError> uploadTooLarge() {
        return ResponseEntity.status(413).body(new ReportStore.ReportError(
            "FILE_TOO_LARGE", null, "O arquivo deve ter no máximo 1 MiB."));
    }
}
