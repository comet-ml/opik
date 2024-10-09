import React from 'react';
import Content from '@theme-original/DocItem/Content';
import type ContentType from '@theme/DocItem/Content';
import type {WrapperProps} from '@docusaurus/types';
import {useLocation} from '@docusaurus/router';
import useDocusaurusContext from '@docusaurus/useDocusaurusContext';
import clsx from 'clsx';

import styles from './styles.module.css';

import Tip from '@theme/Admonition/Icon/Tip';

type Props = WrapperProps<typeof ContentType>;

function NotebookBanner() {
  const location = useLocation();
  const {siteConfig} = useDocusaurusContext();
  
  const isCookbook = location.pathname.includes('cookbook/');
  const notebookPath = location.pathname
    .replace(siteConfig.baseUrl, '/docs/')
    .split('?')[0]  // Remove URL parameters
    .replace(/\/$/, '');  // Remove trailing slash
  const githubNotebookPath = `https://github.com/comet-ml/opik/blob/main/apps/opik-documentation/documentation${notebookPath}.ipynb`
  const colabNotebookPath = `https://colab.research.google.com/github/comet-ml/opik/blob/main/apps/opik-documentation/documentation${notebookPath}.ipynb`

  if (!isCookbook) {
    return null;
  } else {
    return ( 
      <div
        className={clsx(
          styles.notebookBanner,
          'alert',
          'alert--success'
        )}
      >
        <div className={styles.bannerContent}>
          <div className={styles.bannerLeft}>
            <div className={styles.bannerIcon}>
              <Tip />
            </div>
            <div className={styles.bannerText}>
              This is a jupyter notebook
            </div>
          </div>
          <div className={styles.bannerRight}>
            <button
              className={clsx(
                styles.notebookBannerButton,
              )}
              type="button"
              onClick={() => window.open(githubNotebookPath, '_blank')}>
              Open in GitHub
            </button>
            <button
              className={clsx(
                styles.notebookBannerButton,
              )}
              onClick={() => window.open(colabNotebookPath, '_blank')}
            >
              Run on Google Colab
            </button>
          </div>
        </div>
      </div>
    );
  }
  
}

export default function ContentWrapper(props: Props): JSX.Element {
  return (
    <>
      <NotebookBanner />
      <Content {...props} />
    </>
  );
}
