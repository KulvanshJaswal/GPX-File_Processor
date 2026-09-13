const API_BASE = import.meta.env.VITE_API_URL || 'http://localhost:8080';

export type JobStatus = 'QUEUED' | 'PROCESSING' | 'FAILED' | 'COMPLETE';

export interface JobStatusResponse {
  jobId: number;
  status: JobStatus;
  distanceKm: number;
  difficulty: string;
  pdfPath: string;
  errorMessage: string;
}

export interface UploadResponse {
  jobId: number;
  status: string;
}

export async function uploadFile(file: File): Promise<UploadResponse> {
  const formData = new FormData();
  formData.append('file', file);

  const response = await fetch(`${API_BASE}/upload`, {
    method: 'POST',
    body: formData,
  });

  if (!response.ok) {
    const message = await response.text();
    throw new Error(message || 'Upload failed. Please try again.');
  }

  return response.json();
}

export async function getJobStatus(jobId: number): Promise<JobStatusResponse> {
  const response = await fetch(`${API_BASE}/jobs/${jobId}`);
  if (!response.ok) {
    throw new Error(`Failed to fetch job status (${response.status})`);
  }
  return response.json();
}

export function pdfUrl(jobId: number): string {
  return `${API_BASE}/jobs/${jobId}/pdf`;
}
