import { useRef, useState } from 'react';
import type { DragEvent } from 'react';
import { uploadFile } from '../api';

interface Props {
  onUploaded: (jobId: number) => void;
}

export default function UploadScreen({ onUploaded }: Props) {
  const [error, setError] = useState<string | null>(null);
  const [isDragging, setIsDragging] = useState(false);
  const [isUploading, setIsUploading] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);

  const submit = async (file: File) => {
    setError(null);

    if (!file.name.toLowerCase().endsWith('.gpx')) {
      setError('Please choose a .gpx file.');
      return;
    }

    setIsUploading(true);
    try {
      const result = await uploadFile(file);
      onUploaded(result.jobId);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Upload failed. Please try again.');
    } finally {
      setIsUploading(false);
    }
  };

  const handleDrop = (e: DragEvent<HTMLDivElement>) => {
    e.preventDefault();
    setIsDragging(false);
    const file = e.dataTransfer.files?.[0];
    if (file) submit(file);
  };

  return (
    <div className="card upload-card">
      <h1>Upload your hike</h1>
      <p className="muted">Drop a GPX file below, or choose one from your device.</p>

      <div
        className={`dropzone ${isDragging ? 'dropzone--active' : ''}`}
        onDragOver={(e) => {
          e.preventDefault();
          setIsDragging(true);
        }}
        onDragLeave={() => setIsDragging(false)}
        onDrop={handleDrop}
        onClick={() => inputRef.current?.click()}
        role="button"
        tabIndex={0}
      >
        <span className="dropzone-icon">🗺️</span>
        <p>{isUploading ? 'Uploading…' : 'Drag & drop a .gpx file here, or click to browse'}</p>
        <input
          ref={inputRef}
          type="file"
          accept=".gpx"
          hidden
          onChange={(e) => {
            const file = e.target.files?.[0];
            if (file) submit(file);
            e.target.value = '';
          }}
        />
      </div>

      {error && <p className="error-text">{error}</p>}
    </div>
  );
}
