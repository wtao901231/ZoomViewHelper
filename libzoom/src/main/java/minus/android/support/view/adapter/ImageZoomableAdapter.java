package minus.android.support.view.adapter;

import android.graphics.Matrix;
import android.graphics.RectF;
import android.widget.ImageView;

import minus.android.support.view.ZoomViewHelper;

/**
 * Created by tagorewang on 2016/10/20.
 */
public class ImageZoomableAdapter implements ZoomViewHelper.IZoomView {

    private final ImageView imageView;

    public ImageZoomableAdapter(ImageView imageView) {
        this.imageView = imageView;
    }

    @Override
    public boolean hasDrawable() {
        return imageView.getDrawable() != null;
    }

    @Override
    public ZoomViewHelper.ScaleType getScaleType() {
        return ZoomViewHelper.ScaleType.valueOf(imageView.getScaleType().toString());
    }

    @Override
    public void setScaleType(ZoomViewHelper.ScaleType scaleType) {
        imageView.setScaleType(ImageView.ScaleType.valueOf(scaleType.toString()));
    }

    @Override
    public void setImageMatrix(Matrix m) {
        imageView.setImageMatrix(m);
    }

    @Override
    public void getDisplayRect(RectF outRect) {
        if (null == outRect) {
            return;
        }
        outRect.set(0, 0, getIntrinsicWidth(), getIntrinsicHeight());
    }

    @Override
    public int getIntrinsicWidth() {
        if(!hasDrawable()) {
            return 0;
        }
        return imageView.getDrawable().getIntrinsicWidth();
    }

    @Override
    public int getIntrinsicHeight() {
        if(!hasDrawable()) {
            return 0;
        }
        return imageView.getDrawable().getIntrinsicHeight();
    }

}
