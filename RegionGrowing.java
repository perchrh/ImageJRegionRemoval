import ij.IJ;
import ij.ImageStack;
import ij.process.ByteProcessor;

/**
 * This class does 3D region growing with flood-fill, and removes regions below a given size threshold.
 * Free software in the public domain.
 *
 * @author Runar Holen and Marit Hagen
 * @author Per Christian Henden
 */
public class RegionGrowing {
    ImageStack imageStack; // Stack of images.

    final int imageDepth = 256; //number of intensity values a pixel can have

    byte[] mark; // marks processed foreground voxels

    int label = 3;

    int labels = 0;

    int xx, yy, zz; // Maximum size in x,y and z-direction

    int buffer_full = 0;

    int removed = 0;

    int sizeThreshold = 100; // The threshold in voxels for keeping foreground regions.

    boolean preserveBorderObjects = false;

    ByteProcessor[] images;

    int[] sizes = new int[imageDepth]; // Histogram that shows how many pixels a region have.

    boolean[] border = new boolean[imageDepth]; // stores if a region is a border region.

    boolean quit = false; // true if we get an overflow.

    private PixelStack stack;

    /**
     * Constructor
     *
     * @param imageStack     the stack of images
     * @param xx             maximum size in x-direction
     * @param yy             maximum size in y-direction
     * @param zz             maximum size in z-direction (number of images)
     * @param sizeThreshold size threshold for removing regions
     * @param preserveBorderObjects true if you want to keep regions that touch image borders
     * @param percentageToAllocate            percent of foreground to set aside as queue-size.
     * @param showReport     whether or not to show a report after the job is done
     */
    public RegionGrowing(ImageStack imageStack, int xx, int yy, int zz, int sizeThreshold, boolean preserveBorderObjects, double percentageToAllocate, boolean showReport) {
        this.preserveBorderObjects = preserveBorderObjects;
        this.sizeThreshold = sizeThreshold;
        this.xx = xx;
        this.yy = yy;
        this.zz = zz;
        this.imageStack = imageStack;

        long start, stop; // Used to keep track of time.

        images = new ByteProcessor[zz];

        for (int t = 0; t < zz; t++) {
            images[t] = (ByteProcessor) imageStack.getProcessor(t + 1);
        }

        //Keep a data structure for marking which voxels have been flood filled
        this.mark = new byte[xx * yy * zz / 8 + 1];

        // Initialize and start processing regions:
        init();
        IJ.showStatus("Starting processing regions");
        start = System.currentTimeMillis();
        stack = new PixelStack(checkMemoryUsage(), percentageToAllocate);
        seed();
        updateImages(false);
        updateImages(true); // Set foreground to white
        stop = System.currentTimeMillis();

        if (showReport) {
            long minutes = (stop - start) / (60 * 1000);
            long seconds = ((stop - start) / 1000) % 60;
            double foregroundFraction = ((double) checkMemoryUsage() / (double) (xx * yy * zz));

            String report = "Region processing complete.\n" + "Foreground fraction of the volume: "
                    + String.format("%.3f", foregroundFraction) + "\n"
                    + "Nr of regions found: " + labels + "\n"
                    + "Nr of regions removed: " + removed + "\n"
                    + "Queue-size reserved: " + this.stack.getSize() + "\n"
                    + "Maximum queue-size used: " + this.stack.getMax() + "\n"
                    + "Time used: " + minutes + " minutes and " + seconds + " seconds";
            IJ.showMessage(report);
        }
    }

    /**
     * This method is called when you want to update the volume with the regions
     * found so far. Typically called when size-buffer is full..
     *
     * @param finalUpdate Sets all found regions to depth-1 (white) if true.
     */
    private void updateImages(boolean finalUpdate) {
        for (int z = 0; z < zz; z++) {
            for (int y = 0; y < yy; y++) {
                for (int x = 0; x < xx; x++) {
                    int value = get(x, y, z) & 0xff;

                    if (value > 2) {
                        if (sizes[value] >= sizeThreshold) {
                            // Set found regions to 2 if large enough
                            set(x, y, z, 2);
                        } else { // Too small region:
                            if (preserveBorderObjects && border[value]) {
                                // Preserves regions connected to volume border.
                                set(x, y, z, 2);
                            } else {
                                set(x, y, z, 0);
                            } // Sets region to background
                        }
                    } else if (finalUpdate && value == 2) {
                        // Set to white.
                        set(x, y, z, imageDepth - 1);
                    }
                }
            }
        }
    }

    /**
     * Initializes volume.
     * 1=foreground, 0=background. Flood filled foreground will be set to 2 later.
     */
    private void init() {
        for (int z = 0; z < zz; z++) {
            for (int y = 0; y < yy; y++) {
                for (int x = 0; x < xx; x++) {
                    if (get(x, y, z) != 0) {
                        set(x, y, z, 1);
                    }
                }
            }
        }
    }

    /**
     * Goes through the whole volume and calls the regionGrow-routine on all
     * voxels which are not previously found or background.
     */
    private void seed() {
        for (int z = 0; z < zz; z++) {
            for (int y = 0; y < yy; y++) {
                for (int x = 0; x < xx; x++) {
                    if (get(x, y, z) == 1) {
                        regionGrow(x, y, z);
                    }
                }
            }
        }
    }

    /**
     * Get the value of a voxel
     *
     * @param x X-coordinate of voxel
     * @param y Y-coordinate of voxel
     * @param z Z-coordinate of voxel
     * @return byte Value of voxel
     */
    private byte get(int x, int y, int z) {
        return ((byte[]) images[z].getPixels())[xx * y + x];
    }

    /**
     * Set the value of a voxel
     *
     * @param x     X-coordinate of voxel
     * @param y     Y-coordinate of voxel
     * @param z     Z-coordinate of voxel
     * @param value value for voxel.
     */
    private void set(int x, int y, int z, int value) {
        ((byte[]) images[z].getPixels())[xx * y + x] = (byte) value;
    }

    /**
     * Region-growing routine implemented with flood-fill.
     *
     * @param x X-coordinate of voxel
     * @param y Y-coordinate of voxel
     * @param z Z-coordinate of voxel
     */
    private void regionGrow(int x, int y, int z) {
        stack.clear();
        stack.insert(x, y, z); // Add first voxel

        // Flood-fill routine: (3D, 6-connected)
        int px, py, pz, queue_pointer;

        while (!stack.isEmpty()) {
            queue_pointer = stack.remove();
            px = stack.getX(queue_pointer);
            py = stack.getY(queue_pointer);
            pz = stack.getZ(queue_pointer);

            sizes[label]++; // histogram of sizes
            set(px, py, pz, label);

            // Add new points to stack:
            if (px - 1 >= 0) {
                if (!getMark(px - 1, py, pz) && get(px - 1, py, pz) == 1) {
                    stack.insert(px - 1, py, pz);
                    setMark(px - 1, py, pz);
                }
            } else {
                border[label] = true;
            }
            if (px + 1 < xx) {
                if (!getMark(px + 1, py, pz) && get(px + 1, py, pz) == 1) {
                    stack.insert(px + 1, py, pz);
                    setMark(px + 1, py, pz);
                }
            } else {
                border[label] = true;
            }
            if (py - 1 >= 0) {
                if (!getMark(px, py - 1, pz) && get(px, py - 1, pz) == 1) {
                    stack.insert(px, py - 1, pz);
                    setMark(px, py - 1, pz);
                }
            } else {
                border[label] = true;
            }
            if (py + 1 < yy) {
                if (!getMark(px, py + 1, pz) && get(px, py + 1, pz) == 1) {
                    stack.insert(px, py + 1, pz);
                    setMark(px, py + 1, pz);
                }
            } else {
                border[label] = true;
            }
            if (pz - 1 >= 0) {
                if (!getMark(px, py, pz - 1) && get(px, py, pz - 1) == 1) {
                    stack.insert(px, py, pz - 1);
                    setMark(px, py, pz - 1);
                }
            } else {
                border[label] = true;
            }
            if (pz + 1 < zz) {
                if (!getMark(px, py, pz + 1) && get(px, py, pz + 1) == 1) {
                    stack.insert(px, py, pz + 1);
                    setMark(px, py, pz + 1);
                }
            } else {
                border[label] = true;
            }

            if (quit) {
                break;
            } // If we have had a memory overflow
        }
        if (sizes[label] < sizeThreshold) {
            removed++;
        }
        label++; // Increase label for next seedPoint
        labels++; // Increase labels to see how many different regions we have..

        if (label == imageDepth) {
            label = 3;
            buffer_full++;
            updateImages(false);
            for (int t = 0; t < imageDepth; t++) { // reset histogram
                sizes[t] = 0;
                border[t] = false;
            }
            IJ.showStatus("Buffer flushed: " + buffer_full);
        }
    }

    /**
     * Checks how much of the volume is foreground, and how much is
     * background and returns nr of foreground-bytes.
     *
     * @return int The number of foreground voxels
     */
    private int checkMemoryUsage() {
        int foreground = 0;

        for (int z = 0; z < zz; z++) {
            for (int y = 0; y < yy; y++) {
                for (int x = 0; x < xx; x++) {
                    int value = get(x, y, z) & 0xff;

                    if (value != 0) {
                        foreground++;
                    }
                }
            }
        }
        return foreground;
    }


    /**
     * Call this method to see if a voxel is visited or not
     *
     * @param x Voxels x-coordinate
     * @param y Voxels y-coordinate
     * @param z Voxels z-coordinate
     * @return boolean Mark. true if current voxel is marked (visited)
     */
    private boolean getMark(int x, int y, int z) {
        // NOTE: Boolean is implemented as a byte on most JVMs.
        // For memory-efficiency, I therefore had to implement
        // my initial boolean[] mark-array as a byte-array and do
        // bit-shifting instead, for memory efficiency.
        // That is why the methods getMark and setMark are a bit complex

        int pointer = xx * yy * z + xx * y + x;
        int b = pointer / 8;
        byte nr = (byte) (pointer % 8 + 1);
        byte bit = (byte) (((byte) (mark[b] << (nr - 1))) >> 7);

        if (bit == -1) {
            return true;
        } // Because right shift shifts in 1's on neg numbers.

        return false;

    }

    /**
     * Sets the given voxel to "visited"
     *
     * @param x Voxels x-coordinate
     * @param y Voxels y-coordinate
     * @param z Voxels z-coordinate
     */
    private void setMark(int x, int y, int z) {
        int pointer = xx * yy * z + xx * y + x;
        int b = pointer / 8;
        byte nr = (byte) (pointer % 8 + 1);
        int tmp = 1 << (8 - nr);

        mark[b] = (byte) (mark[b] | tmp);
    }

    /**
     * Custom-made stack to optimize memory-usage.
     */
    private class PixelStack {

        private final short[] queuex, queuey, queuez; //stores pixel coordinates

        private final int size; //size of the stack

        private int first = 0; //pointer to array index where queue starts
        private int last = 0; //pointer to array index where queue ends
        private int max = 0; //max queue size utilized, used for logging purposes

        private PixelStack(final int mem, final double pro) {
            size = Math.max((int) (mem * pro / 100), 128); //queue size is at least 128 elements, for efficiency reasons
            queuex = new short[size];
            queuey = new short[size];
            queuez = new short[size];
        }

        /**
         * Used to remove a voxel from the queue
         *
         * @return int Pointer to removed voxel
         */
        private int remove() {
            first++;

            if (first == size) { //we wrapped around when popping the stack
                first = 0;
                return size - 1;
            }
            return first - 1;
        }

        /**
         * Used to insert a new voxel in the queue
         *
         * @param x Voxels x-coordinate
         * @param y Voxels y-coordinate
         * @param z Voxels z-coordinate
         */
        private void insert(int x, int y, int z) {
            queuex[last] = (short) x;
            queuey[last] = (short) y;
            queuez[last] = (short) z;

            last++;

            if (last == size) {
                last = 0;      //we wrapped around when adding a new item, stack now ends at index 0
            }

            if (last == first) {
                IJ.showMessage("FIFO overflow!");
                quit = true;
            }

            updateMax();
        }

        private void updateMax() {
            if (last < first) {  //queue crosses array end
                if (last + size - first > max) {
                    max = last + size - first;
                }
            } else if (last - first > max) {
                max = last - first;
            }
        }

        public int getSize() {
            return size;
        }

        public boolean isEmpty() {
            return first == last;
        }

        public void clear() {
            last = 0;
            first = 0;
        }

        public int getX(int queue_pointer) {
            return queuex[queue_pointer];
        }


        public int getY(int queue_pointer) {
            return queuey[queue_pointer];
        }


        public int getZ(int queue_pointer) {
            return queuez[queue_pointer];
        }

        public int getMax() {
            return max;
        }

    }

}
