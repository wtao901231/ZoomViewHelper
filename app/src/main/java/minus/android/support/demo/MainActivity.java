package minus.android.support.demo;

import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.widget.ImageView;

import minus.android.support.view.ZoomViewHelper;
import minus.android.support.view.adapter.ImageZoomableAdapter;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        final ImageView imageView = (ImageView) findViewById(R.id.zoom_img);
        ZoomViewHelper zoomViewHelper = new ZoomViewHelper(imageView, new ImageZoomableAdapter(imageView));
        zoomViewHelper.update();
    }
}
