#!/usr/bin/env python3
"""
Converter for MP (multiplex) project QuPath CSV files to AnnData format.
Adapted from multiplex_to_anndata.py for the MP panel and column naming conventions.

MP panel markers: DAPI, CD8, TCR, GrB, HLA-DR, PD-1, Pan-CK, Autofluorescence
Spatial coords:   CentroidX_um / CentroidY_um
Cell ID col:      CellID
Area col:         Area_um2
"""

import pandas as pd
import numpy as np
import anndata as ad
from pathlib import Path
from typing import List, Optional
import warnings
import glob
import os

warnings.filterwarnings('ignore')

EXCEL_ERROR_VALUES = {
    "#NUM!", "#DIV/0!", "#VALUE!", "#REF!", "#NAME?", "#N/A", "#NULL!"
}


class MPToAnnData:
    """Convert MP project QuPath CSV files to AnnData objects."""

    def __init__(self):
        self.markers = ['DAPI', 'CD8', 'TCR', 'GrB', 'HLA-DR', 'PD-1', 'Pan-CK', 'Autofluorescence']
        self.compartment = 'Cell'

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def convert(self, csv_path: str, sample_id: Optional[str] = None) -> ad.AnnData:
        """Convert a single MP CSV file to AnnData."""
        df = pd.read_csv(csv_path)
        df.replace(EXCEL_ERROR_VALUES, np.nan, inplace=True)

        if sample_id is None:
            sample_id = Path(csv_path).name
            # Strip scan/extension suffixes
            for suffix in ['.ome.tif.csv', '.ome.tif', '.csv']:
                if sample_id.endswith(suffix):
                    sample_id = sample_id[: -len(suffix)]

        print(f"\nProcessing: {Path(csv_path).name}")
        print(f"  Cells (raw): {len(df):,}")

        # Drop cells contained within Ignore annotations
        if 'ContainingAnnotations' in df.columns:
            ignore_mask = df['ContainingAnnotations'].str.contains(r'\[Ignore\]', na=False)
            n_ignored = ignore_mask.sum()
            if n_ignored > 0:
                df = df[~ignore_mask].reset_index(drop=True)
                print(f"  Dropped {n_ignored:,} Ignore cells → {len(df):,} remaining")

        print(f"  Cells: {len(df):,}")

        marker_features = self._select_marker_features(df)
        morphology_features = self._select_morphology_features(df)
        all_features = marker_features + morphology_features

        print(f"  Markers: {[f.split(':')[0].strip() for f in marker_features]}")
        print(f"  Morphology features: {morphology_features}")

        X = df[all_features].values.astype(np.float32)
        var = self._create_var(all_features, marker_features, morphology_features)
        obs = self._create_obs(df, sample_id, var)

        adata = ad.AnnData(X=X, obs=obs, var=var)

        # Spatial coordinates
        if 'CentroidX_um' in df.columns and 'CentroidY_um' in df.columns:
            adata.obsm['spatial'] = df[['CentroidX_um', 'CentroidY_um']].values.astype(np.float32)
            adata.obsm['X_spatial'] = adata.obsm['spatial']

        # Distance columns → obs
        distance_cols = [
            col for col in df.columns
            if 'Distance to' in col or 'Signed distance to' in col
        ]
        if distance_cols:
            for col in distance_cols:
                safe = (
                    col.lower()
                    .replace(' µm', '_um')
                    .replace(' ', '_')
                    .replace('/', '_')
                    .replace(':', '_')
                    .strip('_')
                )
                adata.obs[safe] = df[col].values.astype(np.float32)
            adata.uns['distance_obs_cols'] = [
                c for c in adata.obs.columns if 'distance' in c
            ]

        print(f"  Created AnnData: {adata.n_obs:,} cells × {adata.n_vars} features")
        return adata

    def convert_multiple(self, csv_files: List[str], merge: bool = True):
        """Convert multiple CSV files and optionally merge into a single AnnData."""
        adata_list = []

        for csv_file in csv_files:
            try:
                adata = self.convert(csv_file)
                adata_list.append(adata)
            except Exception as e:
                print(f"  ✗ Error processing {csv_file}: {e}")

        if not merge:
            return adata_list

        if len(adata_list) == 0:
            raise ValueError("No files were successfully converted.")

        if len(adata_list) == 1:
            return adata_list[0]

        print(f"\nMerging {len(adata_list)} samples...")
        sample_keys = [adata.obs['sample_id'].iloc[0] for adata in adata_list]

        adata_merged = ad.concat(
            adata_list,
            merge='same',
            label='merge_key',
            keys=sample_keys,
            index_unique='_',
        )

        if 'merge_key' in adata_merged.obs.columns:
            adata_merged.obs.drop(columns=['merge_key'], inplace=True)

        print(f"Merged: {adata_merged.n_obs:,} cells × {adata_merged.n_vars} features")
        return adata_merged

    # ------------------------------------------------------------------
    # Private helpers
    # ------------------------------------------------------------------

    def _select_marker_features(self, df: pd.DataFrame) -> List[str]:
        selected = []
        for marker in self.markers:
            col = f"{marker}: {self.compartment}: Mean"
            if col in df.columns:
                selected.append(col)
        return selected

    def _select_morphology_features(self, df: pd.DataFrame) -> List[str]:
        selected = []
        if 'Area_um2' in df.columns:
            selected.append('Area_um2')
        return selected

    def _create_obs(self, df: pd.DataFrame, sample_id: str, var: pd.DataFrame) -> pd.DataFrame:
        # Cell index
        if 'CellID' in df.columns:
            cell_ids = [f"{sample_id}_{cid}" for cid in df['CellID']]
        else:
            cell_ids = [f"{sample_id}_cell_{i}" for i in range(len(df))]

        obs = pd.DataFrame(index=cell_ids)
        obs.index.name = 'cell_id'

        obs['sample_id'] = sample_id
        obs['imageid'] = sample_id  # SCIMAP compatibility
        obs['spatial_unit'] = 'µm'

        # QuPath classification / annotation columns
        qupath_cols = ['Classification', 'Image', 'ParentAnnotations', 'ContainingAnnotations', 'Geometry_um']
        for col in qupath_cols:
            if col in df.columns:
                safe = col.lower().replace(' ', '_').replace('/', '_')
                obs[safe] = df[col].values

        # Metadata already baked into each CSV by add_metadata notebook
        meta_cols = ['Batch number', 'Type of cancer', 'Main cancer grouping', 'Category']
        for col in meta_cols:
            if col in df.columns:
                obs[col] = df[col].values

        # Normalise all column names: lower-case, spaces/slashes → underscore
        obs.columns = obs.columns.str.lower().str.replace(r'[ /]+', '_', regex=True)

        return obs

    def _create_var(self, all_features: List[str],
                    marker_features: List[str],
                    morphology_features: List[str]) -> pd.DataFrame:
        var = pd.DataFrame(index=all_features)
        var['feature_type'] = [
            'marker' if f in marker_features else 'morphology'
            for f in all_features
        ]
        var['marker'] = ''
        var['compartment'] = ''
        var['statistic'] = ''

        for feature in all_features:
            if ':' in feature:
                parts = feature.split(':')
                var.loc[feature, 'marker'] = parts[0].strip()
                if len(parts) >= 2:
                    var.loc[feature, 'compartment'] = parts[1].strip()
                if len(parts) >= 3:
                    var.loc[feature, 'statistic'] = parts[2].strip()

        # Sanitize var index: colons/spaces → underscore, strip leading/trailing _
        var.index = (
            var.index.str.replace(r'[:\s]+', '_', regex=True).str.strip('_')
        )
        return var


# ------------------------------------------------------------------
# Utility
# ------------------------------------------------------------------

def find_mp_csv_files(directory: str, exclude: Optional[List[str]] = None) -> List[str]:
    """Return sorted list of .ome.tif.csv files in directory, excluding listed filenames."""
    exclude = set(exclude or [])
    files = sorted(glob.glob(os.path.join(directory, '*.ome.tif.csv')))
    return [f for f in files if os.path.basename(f) not in exclude]
