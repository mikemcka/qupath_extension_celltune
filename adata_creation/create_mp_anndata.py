#!/usr/bin/env python3
"""
Entry point: convert all MP project scan CSV files to a merged AnnData (.h5ad).
Output: MP_all_samples.h5ad in the same directory as this script.
"""

import sys
import os
from pathlib import Path

# Ensure the project directory is on the path
project_dir = Path(__file__).resolve().parent
sys.path.insert(0, str(project_dir))

from mp_to_anndata import MPToAnnData, find_mp_csv_files

def main():
    print("=" * 80)
    print("MP Project — AnnData Creation")
    print("=" * 80)

    csv_dir = str(project_dir)
    output_path = project_dir / "MP_all_samples.h5ad"

    # Find all scan CSV files
    csv_files = find_mp_csv_files(csv_dir)
    print(f"\nFound {len(csv_files)} scan CSV files in: {csv_dir}")
    for f in csv_files:
        print(f"  {Path(f).name}")

    if not csv_files:
        print("ERROR: No CSV files found — exiting.")
        return 1

    # Convert and merge
    print("\n" + "=" * 80)
    print("Converting...")
    print("=" * 80)

    converter = MPToAnnData()
    adata = converter.convert_multiple(csv_files, merge=True)

    # Summary
    print("\n" + "=" * 80)
    print("SUMMARY")
    print("=" * 80)
    print(adata)
    print(f"\nCells per sample:")
    print(adata.obs['sample_id'].value_counts().to_string())

    # Save
    print(f"\nSaving to: {output_path}")
    adata.write_h5ad(output_path)
    print(f"✓ Saved: {output_path}")
    print(f"  File size: {output_path.stat().st_size / 1024**2:.1f} MB")

    return 0


if __name__ == '__main__':
    sys.exit(main())
