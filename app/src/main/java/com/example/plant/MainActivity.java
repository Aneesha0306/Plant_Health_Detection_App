package com.example.plant;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;
import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.support.common.FileUtil;
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {
    private static final int REQUEST_CODE_CAMERA = 101;
    private Button cameraButton, galleryButton;
    private ImageView imageView;
    private TextView resultTextView;
    private Interpreter tfliteInterpreter;
    private Bitmap originalBitmap;
    private final int imageSize = 150;

    private static final String GEMINI_API_KEY = "your_api_key";
    private static final String GEMINI_URL = "https://generativelanguage.googleapis.com/v1/models/gemini-1.5-flash:generateContent?key=" + GEMINI_API_KEY;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        cameraButton = findViewById(R.id.camera_button);
        galleryButton = findViewById(R.id.gallery_button);
        imageView = findViewById(R.id.imageView);
        resultTextView = findViewById(R.id.resultTextView);
        imageView.setImageResource(R.drawable.logo);

        try {
            tfliteInterpreter = new Interpreter(FileUtil.loadMappedFile(this, "model.tflite"));
        } catch (IOException e) {
            resultTextView.setText("Error loading model.");
            Log.e("ModelError", "Error loading TensorFlow Lite model", e);
        }

        cameraButton.setOnClickListener(v -> {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                cameraLauncher.launch(intent);
            } else {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQUEST_CODE_CAMERA);
            }
        });

        galleryButton.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            imagePickerLauncher.launch(intent);
        });
    }

    private final ActivityResultLauncher<Intent> cameraLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Bitmap bitmap = (Bitmap) result.getData().getExtras().get("data");
                    processImage(bitmap);
                }
            });

    private final ActivityResultLauncher<Intent> imagePickerLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Uri imageUri = result.getData().getData();
                    try {
                        Bitmap bitmap = MediaStore.Images.Media.getBitmap(getContentResolver(), imageUri);
                        processImage(bitmap);
                    } catch (IOException e) {
                        resultTextView.setText("Error loading image");
                    }
                }
            });

    private void processImage(@Nullable Bitmap bitmap) {
        resultTextView.setText("Predicting......");
        if (bitmap != null) {
            bitmap = resizeImage(bitmap, 300);
            imageView.setImageBitmap(bitmap);
            originalBitmap = bitmap;
            Log.d("ImageProcessing", "Image resized and set in ImageView");
            new CheckLeafTask().execute(bitmap);
        } else {
            resultTextView.setText("Error: Image not found");
        }
    }

    private class CheckLeafTask extends AsyncTask<Bitmap, Void, Boolean> {
        @Override
        protected Boolean doInBackground(Bitmap... bitmaps) {
            try {
                String base64Image = convertBitmapToBase64(bitmaps[0]);
                JSONObject requestBody = new JSONObject();
                JSONArray contents = new JSONArray();
                JSONObject contentObject = new JSONObject();
                JSONArray parts = new JSONArray();

                parts.put(new JSONObject().put("text", "Does this image contain a leaf? Answer only 'yes' or 'no'."));
                parts.put(new JSONObject().put("inlineData", new JSONObject()
                        .put("mimeType", "image/jpeg")
                        .put("data", base64Image)));

                contentObject.put("parts", parts);
                contents.put(contentObject);
                requestBody.put("contents", contents);

                OkHttpClient client = new OkHttpClient();
                RequestBody body = RequestBody.create(MediaType.parse("application/json"), requestBody.toString());
                Request request = new Request.Builder().url(GEMINI_URL).post(body).build();

                Response response = client.newCall(request).execute();
                Log.d("APICall", "Requesting URL: " + GEMINI_URL);

                if (!response.isSuccessful()) {
                    Log.d("APIResponse", "Response received: " + response.code());
                    return false;
                }

                JSONObject jsonResponse = new JSONObject(response.body().string());
                Log.d("APIResponse", "Response JSON: " + jsonResponse.toString());

                JSONArray candidates = jsonResponse.optJSONArray("candidates");
                if (candidates != null && candidates.length() > 0) {
                    return candidates.getJSONObject(0).getJSONObject("content")
                            .getJSONArray("parts").getJSONObject(0)
                            .getString("text").toLowerCase().contains("yes");
                }
                return false;
            } catch (Exception e) {
                Log.e("APIError", "Exception in API call", e);
                return false;
            }
        }

        @Override
        protected void onPostExecute(Boolean isLeafPresent) {
            resultTextView.setText(isLeafPresent ? predictImage(originalBitmap) : "Leaf missing");
        }
    }

    private String predictImage(Bitmap image) {
        try {
            // Ensure image format is ARGB_8888
            Bitmap resizedImage = Bitmap.createScaledBitmap(image, imageSize, imageSize, false);
            resizedImage = resizedImage.copy(Bitmap.Config.ARGB_8888, true);

            TensorBuffer inputBuffer = TensorBuffer.createFixedSize(new int[]{1, imageSize, imageSize, 3}, DataType.FLOAT32);
            ByteBuffer byteBuffer = ByteBuffer.allocateDirect(4 * imageSize * imageSize * 3);
            byteBuffer.order(ByteOrder.LITTLE_ENDIAN);  // Ensure consistent byte order

            int[] intValues = new int[imageSize * imageSize];
            resizedImage.getPixels(intValues, 0, imageSize, 0, 0, imageSize, imageSize);

            int pixel = 0;
            for (int i = 0; i < imageSize; i++) {
                for (int j = 0; j < imageSize; j++) {
                    int val = intValues[pixel++];

                    // Extract RGB values and normalize them to [0,1]
                    float red = ((val >> 16) & 0xFF) / 255.0f;
                    float green = ((val >> 8) & 0xFF) / 255.0f;
                    float blue = (val & 0xFF) / 255.0f;

                    byteBuffer.putFloat(red);
                    byteBuffer.putFloat(green);
                    byteBuffer.putFloat(blue);
                }
            }

            inputBuffer.loadBuffer(byteBuffer);

            TensorBuffer outputBuffer = TensorBuffer.createFixedSize(new int[]{1, 1}, DataType.FLOAT32);
            tfliteInterpreter.run(inputBuffer.getBuffer(), outputBuffer.getBuffer());

            float result = outputBuffer.getFloatArray()[0];

            // Log the result for debugging
            Log.d("ML_DEBUG", "Prediction result: " + result);

            return result > 0.5 ? "Healthy" : "Unhealthy";

        } catch (Exception e) {
            e.printStackTrace();
            return "Error in Prediction";
        }
    }



    private Bitmap resizeImage(Bitmap bitmap, int maxSize) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        float scale = Math.min((float) maxSize / width, (float) maxSize / height);
        return Bitmap.createScaledBitmap(bitmap, (int) (width * scale), (int) (height * scale), true);
    }

    private String convertBitmapToBase64(Bitmap bitmap) {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, byteArrayOutputStream);
        return Base64.encodeToString(byteArrayOutputStream.toByteArray(), Base64.NO_WRAP);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.e("ActivityLifecycle", "onDestroy called - Activity is being destroyed.");
        if (tfliteInterpreter != null) {
            tfliteInterpreter.close();
        }
    }

}