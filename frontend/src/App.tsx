import { useState } from 'react';
import type { JobStatusResponse } from './api';
import UploadScreen from './components/UploadScreen';
import PollingScreen from './components/PollingScreen';
import SuccessScreen from './components/SuccessScreen';
import FailureScreen from './components/FailureScreen';
import HeroBackground from './HeroBackground';
import './App.css';

type Stage = 'upload' | 'polling' | 'success' | 'failure';

function App() {
  const [stage, setStage] = useState<Stage>('upload');
  const [jobId, setJobId] = useState<number | null>(null);
  const [jobData, setJobData] = useState<JobStatusResponse | null>(null);

  const handleUploaded = (id: number) => {
    setJobId(id);
    setStage('polling');
  };

  const handleSettled = (data: JobStatusResponse) => {
    setJobData(data);
    setStage(data.status === 'COMPLETE' ? 'success' : 'failure');
  };

  const handleReset = () => {
    setJobId(null);
    setJobData(null);
    setStage('upload');
  };

  return (
    <HeroBackground>
      <main className="app-shell">
        <header className="app-header">
          <span className="app-brand">🥾 Trailhead</span>
          <p className="app-tagline">Upload a hike, get a trail report</p>
        </header>

        {stage === 'upload' && <UploadScreen onUploaded={handleUploaded} />}
        {stage === 'polling' && jobId !== null && (
          <PollingScreen jobId={jobId} onSettled={handleSettled} />
        )}
        {stage === 'success' && jobData && <SuccessScreen job={jobData} onReset={handleReset} />}
        {stage === 'failure' && jobData && <FailureScreen job={jobData} onReset={handleReset} />}
      </main>
    </HeroBackground>
  );
}

export default App;
