package com.popnup.popnupbackend.domain.reservation.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.popnup.popnupbackend.domain.reservation.exception.ReservationErrorCode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class QrService {

  private static final int QR_SIZE = 250;
  private static final String IMAGE_FORMAT = "PNG";

  private static final Map<EncodeHintType, Object> HINTS =
      Map.of(
          EncodeHintType.CHARACTER_SET,
          StandardCharsets.UTF_8.name(),
          EncodeHintType.MARGIN,
          1,
          EncodeHintType.ERROR_CORRECTION,
          ErrorCorrectionLevel.M);

  public byte[] generateQrCodeImage(String content) {
    try {
      BitMatrix bitMatrix =
          new MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, QR_SIZE, QR_SIZE, HINTS);

      ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
      MatrixToImageWriter.writeToStream(bitMatrix, IMAGE_FORMAT, outputStream);

      return outputStream.toByteArray();
    } catch (WriterException | IOException | IllegalArgumentException exception) {
      log.error("QR Code 생성 실패: text={}", content, exception);
      throw ReservationErrorCode.QR_GENERATION_FAILED.toException();
    }
  }
}
