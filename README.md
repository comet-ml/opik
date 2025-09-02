# Opik - Unilab's Self-Hosted LLM Evaluation Platform

<div align="center">

### [https://52.155.251.75](https://52.155.251.75)

*Click the link above to access the live instance.*
*Make sure you are in the ["Opik Users"](https://portal.azure.com/#view/Microsoft_AAD_IAM/GroupDetailsMenuBlade/~/Overview/groupId/647828ca-5ea9-4084-9bef-009557c71925) group to log in.*

</div>

---

> **Original Project**: [Comet Opik](https://github.com/comet-ml/opik)  
> **This Repository**: Unilab's self-hosted Azure deployment with custom modifications.

## Quick Start

### First-Time Deployment

1. **Configure Environment**
   ```bash
   cd deployment
   cp .env.azure.example .env.azure
   # Edit .env.azure with your Azure credentials
   ```

> [!IMPORTANT]
> Use DevScope's account to run the script.
> Run `az login` with it first.

2. **Deploy to Azure**
   ```bash
   ./deploy-azure.sh
   ```

### Upgrading Versions

```bash
# 1. Merge upstream changes
git fetch upstream && git merge upstream/main

# 2. Update version in deployment/.env.azure
OPIK_VERSION=new_version

# 3. Redeploy (preserves all data)
cd deployment && ./deploy-azure.sh
```

### Cluster Management

#### Delete Cluster
- Go to **Azure Portal** → **Resource Groups** → [**`opik-rg`**](https://portal.azure.com/#@unilabspt.com/resource/subscriptions/dcfd8c01-e074-4660-bfb9-2c793a8a8f3f/resourceGroups/opik-rg/overview)
- Delete the **`opik-aks`** cluster
- ✅ **Your data will be preserved** (stored in persistent disks)

#### Recover Data After Cluster Recreation

The deployment script automatically detects and reuses existing data disks when you redeploy. 
These disks are found in the `opik-rg` resource group, so they are not deleted with the cluster.

Simply run:

```bash
cd deployment
./deploy-azure.sh
```

Your existing data will be automatically preserved and reattached.

## More Information

📁 **Detailed Documentation**: 
See [`deployment/`](deployment/) folder for:
- Complete deployment guide
- Configuration options
- Troubleshooting
- Network architecture

---

*For general Opik documentation and features, visit the [original repository](https://github.com/comet-ml/opik)*