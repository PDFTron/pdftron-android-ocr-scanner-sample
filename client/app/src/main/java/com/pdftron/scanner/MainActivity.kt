package com.pdftron.scanner

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.firebase.FirebaseApp
import com.google.firebase.storage.FirebaseStorage
import com.pdftron.pdf.config.ViewerConfig
import com.pdftron.pdf.controls.DocumentActivity
import com.scanlibrary.ScanConstants
import com.scanlibrary.ScannerContract
import io.reactivex.Single
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var button: MaterialButton
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView

    private val bucket =  "FIREBASE_STORAGE_BUCKET"
    private val cloudFunctionUrl: String =  "CLOUD_FUNCTION_URL"

    private val storage: FirebaseStorage = FirebaseStorage.getInstance(bucket)
    private var client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        FirebaseApp.initializeApp(this)

        // Add callback to handle returned image from scanner
        val scannerLauncher = registerForActivityResult(ScannerContract()) { uri ->
            if (uri != null) {
                try {
                    // Obtain the bitmap and save as a local image file
                    var bitmap: Bitmap? = null
                    bitmap = MediaStore.Images.Media.getBitmap(contentResolver, uri)
                    contentResolver.delete(uri!!, null, null)

                    // Save bitmap to local cache as image then upload for processing
                    val localJpeg = saveBitmapAsJpeg(bitmap)

                    // Process image on server
                    uploadFile(localJpeg)

                    // Show progress UI
                    showProgress()

                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
        }


        button = findViewById(R.id.button)
        progressBar = findViewById(R.id.loading)
        progressText = findViewById(R.id.progress_text)
        button.setOnClickListener {
            // Launch the scanner activity
            scannerLauncher.launch(ScanConstants.OPEN_CAMERA)
        }
    }

    private fun uploadFile(localFile: File) {
        val reference = storage.reference
        val fileName = localFile.name
        val fileReference = reference.child(fileName)
        val uploadTask = fileReference.putFile(Uri.fromFile(localFile))
        // Register observers to listen for when the download is done or if it fails
        uploadTask.addOnSuccessListener { taskSnapshot ->
            Log.d("ScannerSample", "File uploaded")
            OCRCloudFunction(fileName)
        }.addOnFailureListener {
            // Handle unsuccessful uploads
            Log.d("ScannerSample", "File not uploaded")
        }
    }

    private fun OCRCloudFunction(fileName: String) {
        // Call cloud function using HTTP request using OkHttp and RxJava
        Single.create<String> {
            try {
                // Create HTTP request to trigger cloud function
                val httpBuilder = cloudFunctionUrl
                    .toHttpUrlOrNull()!!
                    .newBuilder()
                httpBuilder.addQueryParameter("file", fileName)
                val request: Request = Request.Builder()
                    .url(httpBuilder.build())
                    .build()
                val response = client.newCall(request).execute()
                val body = response.body
                if (response.isSuccessful) {
                    it.onSuccess(body!!.string())
                } else {
                    throw IOException("Unsuccessful")
                }
            } catch (e: IOException) {
                it.onError(e)
            }
        }.apply {
            subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({
                    Log.d("ScannerSample", "Result is = $it")
                    // Download processed file from Firebase Storage
                    val trimmedResult = it.replace("\"", "")
                    downloadStorageFile(trimmedResult)
                    // Optionally, delete uploaded file from Firebase Storage
                    deleteStorageFile(fileName)
                }, {
                    Log.d("ScannerSample", "Error = $it")
                })
        }
    }

    private fun downloadStorageFile(fileName: String) {
        val reference = storage.reference
        val fileReference = reference.child(fileName)
        val localFile = File(cacheDir, fileName)

        fileReference.getFile(localFile).addOnSuccessListener {
            // Local temp file has been created
            Log.d("ScannerSample", "File downloaded")

            // Hide progress bar
            hideProgress()

            // Open processed document in PDF viewer
            val config = ViewerConfig.Builder()
                .openUrlCachePath(cacheDir.absolutePath)
                .build()
            DocumentActivity.openDocument(this@MainActivity, Uri.fromFile(localFile), config)

            // Delete processed file on Firebase Storage
            deleteStorageFile(fileName)

        }.addOnFailureListener {
            // Handle any errors
            Log.d("ScannerSample", "File not downloaded: $it")
        }
    }

    private fun deleteStorageFile(fileName: String) {
        val reference = storage.reference
        val fileReference = reference.child(fileName)
        fileReference.delete()
    }

    private fun showProgress() {
        progressBar.visibility = View.VISIBLE
        progressText.visibility = View.VISIBLE
        button.visibility = View.GONE
    }

    private fun hideProgress() {
        progressBar.visibility = View.GONE
        progressText.visibility = View.GONE
        button.visibility = View.VISIBLE
    }

    private fun saveBitmapAsJpeg(bitmap: Bitmap): File {
        val filesDir: File = filesDir
        val imageFile = File(filesDir, File.createTempFile("image", ".jpg").name)

        val os: OutputStream
        try {
            os = FileOutputStream(imageFile)
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, os)
            os.flush()
            os.close()
        } catch (e: Exception) {
            // ignore
        }

        return imageFile
    }
}