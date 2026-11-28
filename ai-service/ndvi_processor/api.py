from io import BytesIO
import os
from pathlib import Path
from uuid import uuid4

import numpy as np
from fastapi import APIRouter, HTTPException, Response
from PIL import Image
from pydantic import BaseModel, Field
import rasterio
from rasterio.errors import RasterioError
from rasterio.transform import from_bounds
from rasterio.warp import Resampling, reproject, transform_bounds

from .processor import NODATA, compute_ndvi

router = APIRouter(prefix="/ndvi", tags=["NDVI"])
IMAGE_DIRECTORY = Path(os.getenv("PATROL_IMAGE_DIRECTORY", "../.local/patrol-images")).resolve()
WEB_MERCATOR_EDGE = 20037508.342789244
TILE_SIZE = 256


class ProcessRequest(BaseModel):
    tif_path: str = Field(min_length=1, max_length=1024)
    flight_id: int | None = Field(default=None, gt=0)


def image_path(relative: str, *, generated: bool = False) -> Path:
    root = IMAGE_DIRECTORY / "ndvi" if generated else IMAGE_DIRECTORY
    path = (IMAGE_DIRECTORY / relative).resolve()
    if (not path.is_relative_to(root) or not path.is_file()
            or path.suffix.lower() not in {".tif", ".tiff"}):
        raise HTTPException(404, "GeoTIFF path not found in the image directory")
    return path


@router.post("/process")
def process(request: ProcessRequest):
    source = image_path(request.tif_path)
    directory = IMAGE_DIRECTORY / "ndvi"
    directory.mkdir(parents=True, exist_ok=True)
    identifier = uuid4().hex if request.flight_id is None else f"{request.flight_id}-{uuid4().hex}"
    output = directory / f"{identifier}.tif"
    temporary = directory / f".{uuid4().hex}.tif"
    try:
        stats = compute_ndvi(source, temporary)
        temporary.replace(output)
    except (RasterioError, ValueError) as exc:
        raise HTTPException(422, str(exc)) from exc
    finally:
        temporary.unlink(missing_ok=True)
    return {"stats": stats, "ndviPath": output.relative_to(IMAGE_DIRECTORY).as_posix()}


@router.get("/metadata")
def metadata(path: str):
    with rasterio.open(image_path(path, generated=True), driver="GTiff") as source:
        bounds = transform_bounds(source.crs, "EPSG:4326", *source.bounds)
    return {"bounds": bounds}


@router.get("/tile/{z}/{x}/{y}.png")
def tile(z: int, x: int, y: int, path: str):
    if not (0 <= z <= 22 and 0 <= x < 2**z and 0 <= y < 2**z):
        raise HTTPException(404, "Tile not found")
    width = 2 * WEB_MERCATOR_EDGE / (2**z)
    left, top = -WEB_MERCATOR_EDGE + x * width, WEB_MERCATOR_EDGE - y * width
    values = np.full((TILE_SIZE, TILE_SIZE), NODATA, dtype=np.float32)
    with rasterio.open(image_path(path, generated=True), driver="GTiff") as source:
        reproject(rasterio.band(source, 1), values, dst_crs="EPSG:3857", dst_nodata=NODATA,
                  dst_transform=from_bounds(left, top - width, left + width, top, TILE_SIZE, TILE_SIZE),
                  resampling=Resampling.nearest)
    rgba = np.zeros((TILE_SIZE, TILE_SIZE, 4), dtype=np.uint8)
    valid = values != NODATA
    rgba[valid & (values < 0.3)] = (220, 58, 53, 235)
    rgba[valid & (values >= 0.3) & (values < 0.6)] = (248, 198, 48, 235)
    rgba[valid & (values >= 0.6)] = (48, 158, 88, 235)
    png = BytesIO()
    Image.fromarray(rgba).save(png, format="PNG")
    return Response(png.getvalue(), media_type="image/png")
