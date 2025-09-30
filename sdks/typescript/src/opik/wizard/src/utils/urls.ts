import { IS_DEV } from '../lib/constants';

export const getCloudUrl = () => {
  if (IS_DEV) {
    return 'http://localhost:5174';
  }

  return 'https://dev.comet.com/';
};
