package dev.fontlab;
import android.os.ParcelFileDescriptor;
interface IFontService {
    String status() = 0;
    String apply(in ParcelFileDescriptor font, String expectedHash) = 1;
    String restore() = 2;
    String probe() = 3;
    String icons(String tx, String sha256) = 4;
    String diagnostics() = 5;
    String exportSystemComponents(in ParcelFileDescriptor destination) = 6;
    void destroy() = 16777114;
}
