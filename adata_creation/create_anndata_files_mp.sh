#!/bin/bash
#SBATCH --job-name=mp_anndata
#SBATCH --output=/vast/projects/SOLACE2/imalki/Imalki_July2022/MP_project/logs/mp_anndata_%j.log
#SBATCH --error=/vast/projects/SOLACE2/imalki/Imalki_July2022/MP_project/logs/mp_anndata_%j.err
#SBATCH --time=12:00:00
#SBATCH --mem=256G
#SBATCH --cpus-per-task=4

# === Job Configuration ===
# Converts all MP project scan CSV files to a merged AnnData (.h5ad)
# Output: MP_all_samples.h5ad

# === Load environment ===
module purge
module load miniconda3

# Activate environment
conda activate /vast/projects/BAC_shared/conda_envs/yolo_env

echo "=========================================="
echo "MP Project — AnnData Creation"
echo "=========================================="
echo "Starting job on $(hostname) at $(date)"
echo "Job ID: $SLURM_JOB_ID"
echo "Allocated memory: $SLURM_MEM_PER_NODE MB"
echo "CPUs: $SLURM_CPUS_PER_TASK"
echo ""

# === Set paths ===
SCRIPT_DIR="/vast/projects/SOLACE2/imalki/Imalki_July2022/MP_project"
OUTPUT_FILE="${SCRIPT_DIR}/MP_all_samples.h5ad"

# Create log directory if it doesn't exist
mkdir -p ${SCRIPT_DIR}/logs

# Change to project directory so relative imports work
cd ${SCRIPT_DIR}

echo "Project directory: ${SCRIPT_DIR}"
echo "Output file:       ${OUTPUT_FILE}"
echo ""

# === Check Python environment ===
echo "Python version:"
python --version
echo ""

echo "Checking required packages..."
python -c "import anndata; import pandas; import numpy; print('✓ All required packages found')"
echo ""

# === Run conversion ===
echo "=========================================="
echo "Running conversion script..."
echo "=========================================="
echo ""

python ${SCRIPT_DIR}/create_mp_anndata.py

# === Check results ===
echo ""
echo "=========================================="
echo "Job Summary"
echo "=========================================="
echo "Output files:"
ls -lh ${SCRIPT_DIR}/*.h5ad 2>/dev/null || echo "No .h5ad files found!"
echo ""

# Print basic info from output file
if [ -f "${OUTPUT_FILE}" ]; then
    echo "Output file created successfully:"
    ls -lh ${OUTPUT_FILE}

    python -c "
import anndata as ad
try:
    adata = ad.read_h5ad('${OUTPUT_FILE}')
    print(f'  Cells:    {adata.n_obs:,}')
    print(f'  Features: {adata.n_vars}')
    print(f'  Samples:  {adata.obs[\"sample_id\"].nunique()}')
except Exception as e:
    print(f'  Error reading file: {e}')
"
else
    echo "ERROR: Output file not created!"
fi

echo ""
echo "Job finished at $(date)"
echo "=========================================="