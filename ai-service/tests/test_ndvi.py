import tempfile
import unittest
from pathlib import Path

import numpy as np
import rasterio
from rasterio.transform import from_bounds

from ndvi_processor.processor import NODATA, compute_ndvi


class NdviTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.source = Path(directory.name) / "source.tif"
        self.result = Path(directory.name) / "ndvi.tif"

    def write(self, red, nir):
        with rasterio.open(
            self.source, "w", driver="GTiff", count=2, width=len(red), height=1,
            dtype="float32", crs="EPSG:4326", nodata=NODATA,
            transform=from_bounds(115, 36, 115.01, 36.01, len(red), 1),
        ) as output:
            output.write(np.array([[red], [nir]], dtype=np.float32))

    def test_configurable_bands_exclude_invalid_pixels(self):
        self.write([40, 30, 10, NODATA, 0, np.nan], [60, 70, 90, 5, 0, 10])
        stats = compute_ndvi(self.source, self.result, 1, 2)
        self.assertEqual(stats["valid_pixel_count"], 3)
        self.assertAlmostEqual(sum(stats[key] for key in ("red_pct", "yellow_pct", "green_pct")), 100)
        with rasterio.open(self.result) as output:
            self.assertEqual(output.crs.to_epsg(), 4326)
            self.assertEqual(output.read(1, masked=True).count(), 3)

    def test_empty_raster_and_same_band_report_errors(self):
        self.write([0, NODATA], [0, 10])
        with self.assertRaisesRegex(ValueError, "distinct red/NIR bands"):
            compute_ndvi(self.source, self.result, 1, 1)
        with self.assertRaisesRegex(ValueError, "no valid NDVI pixels"):
            compute_ndvi(self.source, self.result, 1, 2)
