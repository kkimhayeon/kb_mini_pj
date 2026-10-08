package kb_bridge.external.dart;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;

class DartClientTest {

    @Test
    void rejectsAnOpenAiKeyBeforeSendingItToOpenDart() {
        DartClient client = new DartClient(
                "https://opendart.fss.or.kr/api",
                "\"sk-test-openai-key\""
        );

        assertThatThrownBy(() -> client.getCompany("00101220"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OPEN_API_KEY is reserved for OpenAI");
    }

    @Test
    void explainsThatOpenDartKeyMustBeConfigured() {
        DartClient client = new DartClient("https://opendart.fss.or.kr/api", " ");

        assertThatThrownBy(() -> client.getCompany("00101220"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Set DART_API_KEY");
    }

    @Test
    void extractsDisclosureTextFromMalformedHtmlInsideDartArchive() throws Exception {
        String malformedHtml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <html><head><meta charset="UTF-8"></head>
                <body><p>유상증자 결정</p><p>투자 금액 100 & 200</p></body></html>
                """;
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(archive)) {
            zip.putNextEntry(new ZipEntry("document.xml"));
            zip.write(malformedHtml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        String text = new DartClient(
                "https://opendart.fss.or.kr/api",
                "0123456789abcdef0123456789abcdef01234567"
        ).extractDisclosureText(archive.toByteArray());

        assertThat(text).contains("유상증자 결정", "투자 금액 100", "200");
    }
}
