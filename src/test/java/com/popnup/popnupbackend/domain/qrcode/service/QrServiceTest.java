package com.popnup.popnupbackend.domain.qrcode.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@Slf4j
class QrServiceTest {

  private final QrService qrService = new QrService();

  @Test
  @DisplayName("텍스트 기반 PNG 바이트 배열 생성 및 콘솔 QR 출력")
  void generateQrCodeImage_logging() {
    // 1. Given (입력값)
    String input = "R20260907TEST1234";
    String expected = "PNG 매직넘버 일치 및 바이너리 생성 (not empty)";

    // 2. When (실제 실행)
    byte[] actualBytes = qrService.generateQrCodeImage(input);
    boolean isPng =
        actualBytes != null
            && actualBytes.length > 4
            && actualBytes[0] == (byte) 0x89
            && actualBytes[1] == (byte) 0x50; // 'P'
    String actual = "크기: " + actualBytes.length + " bytes, PNG 포맷 일치 여부: " + isPng;

    // 3. Log (결과 및 콘솔 QR 시각화)
    String asciiQr = renderAsciiQr(input, 25, 25);

    log.info(
        "\n================ [TEST RUN] ================"
            + "\n📌 테스트명    : PNG 바이트 배열 생성 검증"
            + "\n📥 입력값      : text = {}"
            + "\n🎯 예상 결과   : {}"
            + "\n🔍 실제 결과   : {}"
            + "\n📱 콘솔 QR 스캔 :\n{}"
            + "\n============================================",
        input,
        expected,
        actual,
        asciiQr);

    // 4. Then (검증)
    assertThat(actualBytes).isNotNull().isNotEmpty();
    assertThat(isPng).isTrue();
  }

  /** 터미널/콘솔에 출력 가능한 아스키(Unicode Block) QR 문자열 렌더러 */
  private String renderAsciiQr(String text, int width, int height) {
    try {
      BitMatrix matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, width, height);
      StringBuilder sb = new StringBuilder();

      for (int y = 0; y < matrix.getHeight(); y++) {
        for (int x = 0; x < matrix.getWidth(); x++) {
          sb.append(matrix.get(x, y) ? "██" : "  ");
        }
        sb.append("\n");
      }
      return sb.toString();
    } catch (Exception e) {
      return "QR 렌더링 실패: " + e.getMessage();
    }
  }
}
