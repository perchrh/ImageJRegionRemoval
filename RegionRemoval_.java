import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.GenericDialog;
import ij.plugin.filter.PlugInFilter;
import ij.process.ImageProcessor;

/**
 * Class RegionGrowing. This class does 3D regiongrowing with flood-fill, and
 * removes regions below a given threshold.
 * Free software in the public domain.
 *
 * @author Runar Holen and Marit Hagen
 * @author Per Christian Henden
 */

public class RegionRemoval_ implements PlugInFilter {

    private ImagePlus imRef;

    private ImageStack stack;

    private int thresh;

    private double pro;

    private boolean borderPreserve;

    private boolean showReport = true;

    private boolean quit = false;

    public int setup(String arg, ImagePlus imp) {

        pro = 100.0; // Percent of fibres to allocate memory
        thresh = 600; // The threshold in voxels for keeping fibre regions.
        borderPreserve = false;

        imRef = imp;

        if (arg.equals("about")) {
            // showAbout();
            return DONE;
        }

        GenericDialog gd = new GenericDialog("Small 3D region removal");

        gd.addNumericField("Region size threshold", thresh, 0);
        gd.addMessage("");
        gd.addNumericField("Percent memory use", pro, 1);
        gd.addCheckbox("Preserve borders", borderPreserve);
        gd.addMessage("");
        gd.addCheckbox("Display report", showReport);
        gd.addMessage("(Macro use might interfere with showing a message box.)");

        gd.showDialog();

        if (gd.wasCanceled()) {
            if (imRef != null)
                imRef.unlock();
            quit = true;
        }

        thresh = (int) gd.getNextNumber();
        pro = gd.getNextNumber() / 100;
        borderPreserve = gd.getNextBoolean();
        showReport = gd.getNextBoolean();

        stack = imRef.getStack();

        return DOES_8G;

    }

    void showAbout() {
        IJ.showMessage("About small 3D region removal..",
                "This plugin removes regions smaller than a specified size ");
    }

    public void run(ImageProcessor imp) {
        if (!quit)
            new RegionGrowing(stack, imp.getWidth(), imp.getHeight(), stack
                    .getSize(), thresh, borderPreserve, pro, showReport);
    }

}
