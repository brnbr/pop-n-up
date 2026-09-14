package com.popnup.popnupbackend.domain.qrcode.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.Result;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.popnup.popnupbackend.domain.qrcode.exception.QrErrorCode;
import com.popnup.popnupbackend.global.error.ServiceException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@Slf4j
class QrServiceTest {

  private final QrService qrService = new QrService();

  // PNG 파일 시그니처(매직 바이트): 89 50 4E 47 0D 0A 1A 0A
  private static final byte[] PNG_SIGNATURE = {
    (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
  };

  @Test
  @DisplayName("정상 문자열이면 PNG 시그니처로 시작하는 바이트 배열을 반환한다")
  void generateQrCodeImage_success() {
    String content = "R20260912TEST0001";

    log.info("[generateQrCodeImage.success] input(content={})", content);

    byte[] result = qrService.generateQrCodeImage(content);

    log.info("[generateQrCodeImage.success] resultByteLength={}", result.length);

    assertThat(result).isNotEmpty();
    assertThat(result).startsWith(PNG_SIGNATURE);
  }

  @Test
  @DisplayName("생성된 QR을 다시 디코딩하면 원래 넣은 문자열이 그대로 나온다")
  void generateQrCodeImage_decodedContentMatchesInput() throws Exception {
    String content = "R20260912TEST0001";

    log.info("[generateQrCodeImage.decodedContentMatchesInput] input(content={})", content);

    byte[] result = qrService.generateQrCodeImage(content);
    String decoded = decodeQrCode(result);

    log.info(
        "[generateQrCodeImage.decodedContentMatchesInput] expected={} actualDecoded={}",
        content,
        decoded);

    assertThat(decoded).isEqualTo(content);
  }

  @Test
  @DisplayName("예약번호 형식의 실제 값으로도 디코딩 결과가 정확히 일치한다")
  void generateQrCodeImage_realisticReservationNumber() throws Exception {
    // book()에서 실제로 만드는 형식: "R" + yyyyMMdd + 8자리 UUID 대문자
    String reservationNumber = "R2026091212AB34CD";

    log.info(
        "[generateQrCodeImage.realisticReservationNumber] input(reservationNumber={})",
        reservationNumber);

    byte[] result = qrService.generateQrCodeImage(reservationNumber);
    String decoded = decodeQrCode(result);

    log.info(
        "[generateQrCodeImage.realisticReservationNumber] expected={} actualDecoded={}",
        reservationNumber,
        decoded);

    assertThat(decoded).isEqualTo(reservationNumber);
  }

  @Test
  @DisplayName("빈 문자열이면 QR_GENERATION_FAILED 예외가 발생한다")
  void generateQrCodeImage_emptyContent_throwsQrGenerationFailed() {
    String content = "";

    log.info("[generateQrCodeImage.emptyContent_throwsQrGenerationFailed] input(content=empty)");

    ServiceException exception =
        assertThrows(ServiceException.class, () -> qrService.generateQrCodeImage(content));

    log.info(
        "[generateQrCodeImage.emptyContent_throwsQrGenerationFailed] expectedErrorCode={} actualErrorCode={}",
        QrErrorCode.QR_GENERATION_FAILED,
        exception.getErrorCode());

    assertThat(exception.getErrorCode()).isEqualTo(QrErrorCode.QR_GENERATION_FAILED);
  }

  // QrService가 생성한 PNG 바이트 배열을 다시 이미지로 읽고, zxing으로 디코딩해서 원문 문자열을 복원
  private String decodeQrCode(byte[] pngBytes) throws Exception {
    BufferedImage image = ImageIO.read(new ByteArrayInputStream(pngBytes));
    LuminanceSource source = new BufferedImageLuminanceSource(image);
    BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));

    Result result = new MultiFormatReader().decode(bitmap);
    return result.getText();
  }
}
