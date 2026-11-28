from pathlib import Path

import numpy as np
import rasterio
from rasterio.windows import Window

NODATA = -9999.0


def compute_ndvi(tif_path: str | Path, output_path: str | Path) -> dict[str, float]:
    """Process bands 3 (red) and 4 (NIR) in bounded windows and keep the source CRS."""
    total = red_count = yellow_count = 0
    value_sum = square_sum = 0.0
    minimum, maximum = np.inf, -np.inf
    with rasterio.open(tif_path, driver="GTiff") as source:
        if source.count < 4 or source.crs is None:
            raise ValueError("GeoTIFF must have four bands and a coordinate system")
        profile = dict(driver="GTiff", width=source.width, height=source.height, count=1,
                       crs=source.crs, transform=source.transform, dtype="float32",
                       nodata=NODATA, tiled=True, compress="deflate", BIGTIFF="IF_SAFER")
        with rasterio.open(output_path, "w", **profile) as output:
            for y in range(0, source.height, 512):
                for x in range(0, source.width, 512):
                    window = Window(x, y, min(512, source.width - x), min(512, source.height - y))
                    red, nir = source.read([3, 4], window=window, masked=True, out_dtype="float64").filled(np.nan)
                    with np.errstate(divide="ignore", invalid="ignore"):
                        ndvi = (nir - red) / (nir + red)
                    valid = np.isfinite(ndvi) & (ndvi > -1) & (ndvi <= 1)
                    pixels = np.where(valid, ndvi, NODATA).astype(np.float32)
                    output.write(pixels, 1, window=window)
                    values = pixels[valid]
                    if values.size:
                        total += values.size
                        minimum = min(minimum, float(values.min()))
                        maximum = max(maximum, float(values.max()))
                        value_sum += float(values.sum(dtype=np.float64))
                        square_sum += float(np.square(values, dtype=np.float64).sum())
                        red_count += int((values < 0.3).sum())
                        yellow_count += int(((values >= 0.3) & (values < 0.6)).sum())
    if not total:
        raise ValueError("GeoTIFF has no valid NDVI pixels")
    mean = value_sum / total
    return {
        "min": minimum,
        "max": maximum,
        "mean": mean,
        "std": max(0, square_sum / total - mean * mean) ** 0.5,
        "red_pct": red_count * 100 / total,
        "yellow_pct": yellow_count * 100 / total,
        "green_pct": (total - red_count - yellow_count) * 100 / total,
    }
