/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Base URL for the backend API. Empty string means same origin. */
  readonly VITE_API_BASE?: string
  /** Target host used by the Vite dev server proxy. */
  readonly VITE_API_TARGET?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
