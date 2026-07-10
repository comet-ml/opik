import React, { useState } from 'react';
import { useDispatch, useSelector } from 'react-redux';
import { selectTraces, annotateTraces } from '../store/tracesSlice';

const TraceAnnotation = () => {
  const dispatch = useDispatch();
  const traces = useSelector(selectTraces);
  const [selectedTraces, setSelectedTraces] = useState([]);
  const [annotation, setAnnotation] = useState('');

  const handleSelectTrace = (traceId) => {
    if (selectedTraces.includes(traceId)) {
      setSelectedTraces(selectedTraces.filter(id => id !== traceId));
    } else {
      setSelectedTraces([...selectedTraces, traceId]);
    }
  };

  const handleAnnotateTraces = () => {
    dispatch(annotateTraces(selectedTraces, annotation));
    setSelectedTraces([]);
    setAnnotation('');
  };

  return (
    <div>
      <h1>Trace Annotation</h1>
      <ul>
        {traces.map(trace => (
          <li key={trace.id}>
            <input
              type="checkbox"
              checked={selectedTraces.includes(trace.id)}
              onChange={() => handleSelectTrace(trace.id)}
            />
            {trace.name}
          </li>
        ))}
      </ul>
      <textarea
        value={annotation}
        onChange={(e) => setAnnotation(e.target.value)}
      />
      <button onClick={handleAnnotateTraces}>Annotate Selected Traces</button>
    </div>
  );
};

export default TraceAnnotation;
