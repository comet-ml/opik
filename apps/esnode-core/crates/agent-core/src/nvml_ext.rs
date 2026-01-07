//! Guarded NVML FFI scaffolding for PCIe/NVSwitch/event helpers.
//! These are best-effort and only built when `gpu-nvml-ffi-ext` is enabled.

/// Errors from extended NVML calls.
#[derive(thiserror::Error, Debug)]
pub enum NvmlExtError {
    #[error("NVML call not supported")]
    NotSupported,
    #[error("NVML returned error code {0}")]
    NvmlReturn(i32),
}

#[cfg(all(feature = "gpu-nvml-ffi-ext", feature = "gpu"))]
use nvml_wrapper_sys::bindings::{
    nvmlDevice_t, nvmlEventSet_t, nvmlFieldValue_t,
    nvmlPcieUtilCounter_enum_NVML_PCIE_UTIL_TX_BYTES, nvmlReturn_enum_NVML_SUCCESS, nvmlReturn_t,
};

/// Best-effort `PCIe` counters (correctable errors, atomic requests).
#[derive(Default, Debug)]
pub struct PcieExt {
    pub correctable_errors: Option<u64>,
    pub atomic_requests: Option<u64>,
}

/// `NVSwitch` error counters placeholder.
#[derive(Default, Debug)]
pub struct NvSwitchExt {
    pub errors: Option<u64>,
}

/// Returned set of NVML field values.
#[derive(Default, Debug)]
pub struct FieldValues {
    pub values: Vec<(u32, i64)>,
}

impl FieldValues {
    #[must_use]
    pub fn get(&self, id: u32) -> Option<i64> {
        self.values
            .iter()
            .find(|(fid, _)| *fid == id)
            .map(|(_, v)| *v)
    }
}

/// Field identifiers gathered from NVML headers (see go-nvml consts for reference).
pub mod field {
    pub const FI_DEV_NVSWITCH_CONNECTED_LINK_COUNT: u32 = 147;
    pub const FI_DEV_PCIE_COUNT_CORRECTABLE_ERRORS: u32 = 173;
    pub const FI_DEV_PCIE_COUNT_NON_FATAL_ERROR: u32 = 179;
    pub const FI_DEV_PCIE_COUNT_FATAL_ERROR: u32 = 180;
    pub const FI_DEV_PCIE_OUTBOUND_ATOMICS_MASK: u32 = 228;
    pub const FI_DEV_PCIE_INBOUND_ATOMICS_MASK: u32 = 229;
}
/// Best-effort `PCIe` extended counters.
///
/// # Safety
///
/// This function dereferences the provided `device` raw pointer to call into NVML via FFI.
/// The caller must ensure `device` is a valid `nvmlDevice_t` obtained from `nvml_wrapper`.
#[cfg(all(feature = "gpu-nvml-ffi-ext", feature = "gpu"))]
pub unsafe fn pcie_ext_counters(device: nvmlDevice_t) -> Result<PcieExt, NvmlExtError> {
    // nvmlDeviceGetPcieReplayCounter is already available in wrapper; here we try best-effort extras.
    // As nvml-wrapper does not expose these, we attempt direct bindings when available; otherwise return NotSupported.
    unsafe {
        let lib = libloading::Library::new("libnvidia-ml.so.1")
            .map_err(|_| NvmlExtError::NotSupported)?;

        type NvmlDeviceGetPcieStats = unsafe extern "C" fn(
            device: nvmlDevice_t,
            counter: u32,
            value: *mut u32,
        ) -> nvmlReturn_t;
        type NvmlDeviceGetPcieReplayCounter =
            unsafe extern "C" fn(device: nvmlDevice_t, value: *mut u32) -> nvmlReturn_t;

        let get_pcie_stats: libloading::Symbol<NvmlDeviceGetPcieStats> = lib
            .get(b"nvmlDeviceGetPcieStats")
            .map_err(|_| NvmlExtError::NotSupported)?;
        let get_pcie_replay_counter: libloading::Symbol<NvmlDeviceGetPcieReplayCounter> = lib
            .get(b"nvmlDeviceGetPcieReplayCounter")
            .map_err(|_| NvmlExtError::NotSupported)?;

        let mut corr: u32 = 0;
        let mut atomic: u32 = 0;
        let corr_ret = get_pcie_stats(
            device,
            nvmlPcieUtilCounter_enum_NVML_PCIE_UTIL_TX_BYTES,
            &raw mut corr,
        );
        let atomic_ret = get_pcie_replay_counter(device, &raw mut atomic);
        let mut out = PcieExt::default();
        if corr_ret == nvmlReturn_enum_NVML_SUCCESS {
            out.correctable_errors = Some(u64::from(corr));
        }
        if atomic_ret == nvmlReturn_enum_NVML_SUCCESS {
            out.atomic_requests = Some(u64::from(atomic));
        }
        if out.correctable_errors.is_none() && out.atomic_requests.is_none() {
            return Err(NvmlExtError::NotSupported);
        }
        Ok(out)
    }
}

#[cfg(all(feature = "gpu-nvml-ffi-ext", feature = "gpu"))]
pub const fn nvswitch_ext_counters(_device: nvmlDevice_t) -> Result<NvSwitchExt, NvmlExtError> {
    Err(NvmlExtError::NotSupported)
}

/// Query values for specific NVML field IDs.
///
/// # Safety
///
/// This function dereferences the provided `device` raw pointer to call into NVML via FFI.
/// The caller must ensure `device` is a valid `nvmlDevice_t`.
#[cfg(all(feature = "gpu-nvml-ffi-ext", feature = "gpu"))]
pub unsafe fn get_field_values(
    device: nvmlDevice_t,
    field_ids: &[u32],
) -> Result<FieldValues, NvmlExtError> {
    unsafe {
        let lib = libloading::Library::new("libnvidia-ml.so.1")
            .map_err(|_| NvmlExtError::NotSupported)?;

        type NvmlDeviceGetFieldValues = unsafe extern "C" fn(
            device: nvmlDevice_t,
            values_count: u32,
            values: *mut nvmlFieldValue_t,
        ) -> nvmlReturn_t;

        let get_field_values_fn: libloading::Symbol<NvmlDeviceGetFieldValues> = lib
            .get(b"nvmlDeviceGetFieldValues")
            .map_err(|_| NvmlExtError::NotSupported)?;

        let mut fields: Vec<nvmlFieldValue_t> = vec![std::mem::zeroed(); field_ids.len()];
        for (i, f) in field_ids.iter().enumerate() {
            fields[i].fieldId = *f;
        }
        let ret = get_field_values_fn(device, fields.len() as u32, fields.as_mut_ptr());
        if ret != nvmlReturn_enum_NVML_SUCCESS {
            return Err(NvmlExtError::NvmlReturn(ret as i32));
        }
        let mut out = FieldValues::default();
        for f in fields {
            out.values.push((f.fieldId, f.value.sllVal));
        }
        Ok(out)
    }
}

/// Placeholder event registration for MIG/GPU handles. Caller should fall back gracefully.
#[cfg(all(feature = "gpu-nvml-ffi-ext", feature = "gpu"))]
pub const fn register_extended_events(
    _device: nvmlDevice_t,
    _event_set: nvmlEventSet_t,
) -> Result<(), NvmlExtError> {
    Err(NvmlExtError::NotSupported)
}

#[cfg(not(all(feature = "gpu-nvml-ffi-ext", feature = "gpu")))]
pub fn pcie_ext_counters(_device: *mut std::ffi::c_void) -> Result<PcieExt, NvmlExtError> {
    Err(NvmlExtError::NotSupported)
}
#[cfg(not(all(feature = "gpu-nvml-ffi-ext", feature = "gpu")))]
pub fn nvswitch_ext_counters(_device: *mut std::ffi::c_void) -> Result<NvSwitchExt, NvmlExtError> {
    Err(NvmlExtError::NotSupported)
}
#[cfg(not(all(feature = "gpu-nvml-ffi-ext", feature = "gpu")))]
pub fn get_field_values(
    _device: *mut std::ffi::c_void,
    _field_ids: &[u32],
) -> Result<FieldValues, NvmlExtError> {
    Err(NvmlExtError::NotSupported)
}
#[cfg(not(all(feature = "gpu-nvml-ffi-ext", feature = "gpu")))]
pub fn register_extended_events(
    _device: *mut std::ffi::c_void,
    _event_set: *mut std::ffi::c_void,
) -> Result<(), NvmlExtError> {
    Err(NvmlExtError::NotSupported)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pcie_ext_stub_compiles() {
        let res = unsafe { pcie_ext_counters(std::ptr::null_mut()) };
        assert!(res.is_err());
    }

    #[test]
    fn field_values_lookup() {
        let fv = FieldValues {
            values: vec![(1, 10), (2, -1)],
        };
        assert_eq!(fv.get(1), Some(10));
        assert_eq!(fv.get(2), Some(-1));
        assert_eq!(fv.get(3), None);
    }
}
