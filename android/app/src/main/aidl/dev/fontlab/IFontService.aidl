package dev.fontlab;
import android.os.ParcelFileDescriptor;
interface IFontService {
    String status() = 0;
    String apply(in ParcelFileDescriptor font, String expectedHash) = 1;
    String restore() = 2;
    void destroy() = 16777114;
}
