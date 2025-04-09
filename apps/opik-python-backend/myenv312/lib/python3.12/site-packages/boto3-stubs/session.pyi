"""
Type annotations for boto3.session module.

Copyright 2025 Vlad Emelianov
"""

import sys
from typing import overload

from boto3.exceptions import ResourceNotExistsError as ResourceNotExistsError
from boto3.exceptions import UnknownAPIVersionError as UnknownAPIVersionError
from boto3.resources.factory import ResourceFactory
from botocore.config import Config
from botocore.credentials import Credentials
from botocore.exceptions import DataNotFoundError as DataNotFoundError
from botocore.exceptions import UnknownServiceError as UnknownServiceError
from botocore.hooks import BaseEventHooks
from botocore.loaders import Loader
from botocore.model import ServiceModel as ServiceModel
from botocore.session import Session as BotocoreSession
from mypy_boto3_accessanalyzer.client import AccessAnalyzerClient
from mypy_boto3_account.client import AccountClient
from mypy_boto3_acm.client import ACMClient
from mypy_boto3_acm_pca.client import ACMPCAClient
from mypy_boto3_amp.client import PrometheusServiceClient
from mypy_boto3_amplify.client import AmplifyClient
from mypy_boto3_amplifybackend.client import AmplifyBackendClient
from mypy_boto3_amplifyuibuilder.client import AmplifyUIBuilderClient
from mypy_boto3_apigateway.client import APIGatewayClient
from mypy_boto3_apigatewaymanagementapi.client import ApiGatewayManagementApiClient
from mypy_boto3_apigatewayv2.client import ApiGatewayV2Client
from mypy_boto3_appconfig.client import AppConfigClient
from mypy_boto3_appconfigdata.client import AppConfigDataClient
from mypy_boto3_appfabric.client import AppFabricClient
from mypy_boto3_appflow.client import AppflowClient
from mypy_boto3_appintegrations.client import AppIntegrationsServiceClient
from mypy_boto3_application_autoscaling.client import ApplicationAutoScalingClient
from mypy_boto3_application_insights.client import ApplicationInsightsClient
from mypy_boto3_application_signals.client import CloudWatchApplicationSignalsClient
from mypy_boto3_applicationcostprofiler.client import ApplicationCostProfilerClient
from mypy_boto3_appmesh.client import AppMeshClient
from mypy_boto3_apprunner.client import AppRunnerClient
from mypy_boto3_appstream.client import AppStreamClient
from mypy_boto3_appsync.client import AppSyncClient
from mypy_boto3_apptest.client import MainframeModernizationApplicationTestingClient
from mypy_boto3_arc_zonal_shift.client import ARCZonalShiftClient
from mypy_boto3_artifact.client import ArtifactClient
from mypy_boto3_athena.client import AthenaClient
from mypy_boto3_auditmanager.client import AuditManagerClient
from mypy_boto3_autoscaling.client import AutoScalingClient
from mypy_boto3_autoscaling_plans.client import AutoScalingPlansClient
from mypy_boto3_b2bi.client import B2BIClient
from mypy_boto3_backup.client import BackupClient
from mypy_boto3_backup_gateway.client import BackupGatewayClient
from mypy_boto3_backupsearch.client import BackupSearchClient
from mypy_boto3_batch.client import BatchClient
from mypy_boto3_bcm_data_exports.client import BillingandCostManagementDataExportsClient
from mypy_boto3_bcm_pricing_calculator.client import BillingandCostManagementPricingCalculatorClient
from mypy_boto3_bedrock.client import BedrockClient
from mypy_boto3_bedrock_agent.client import AgentsforBedrockClient
from mypy_boto3_bedrock_agent_runtime.client import AgentsforBedrockRuntimeClient
from mypy_boto3_bedrock_data_automation.client import DataAutomationforBedrockClient
from mypy_boto3_bedrock_data_automation_runtime.client import RuntimeforBedrockDataAutomationClient
from mypy_boto3_bedrock_runtime.client import BedrockRuntimeClient
from mypy_boto3_billing.client import BillingClient
from mypy_boto3_billingconductor.client import BillingConductorClient
from mypy_boto3_braket.client import BraketClient
from mypy_boto3_budgets.client import BudgetsClient
from mypy_boto3_ce.client import CostExplorerClient
from mypy_boto3_chatbot.client import ChatbotClient
from mypy_boto3_chime.client import ChimeClient
from mypy_boto3_chime_sdk_identity.client import ChimeSDKIdentityClient
from mypy_boto3_chime_sdk_media_pipelines.client import ChimeSDKMediaPipelinesClient
from mypy_boto3_chime_sdk_meetings.client import ChimeSDKMeetingsClient
from mypy_boto3_chime_sdk_messaging.client import ChimeSDKMessagingClient
from mypy_boto3_chime_sdk_voice.client import ChimeSDKVoiceClient
from mypy_boto3_cleanrooms.client import CleanRoomsServiceClient
from mypy_boto3_cleanroomsml.client import CleanRoomsMLClient
from mypy_boto3_cloud9.client import Cloud9Client
from mypy_boto3_cloudcontrol.client import CloudControlApiClient
from mypy_boto3_clouddirectory.client import CloudDirectoryClient
from mypy_boto3_cloudformation.client import CloudFormationClient
from mypy_boto3_cloudformation.service_resource import CloudFormationServiceResource
from mypy_boto3_cloudfront.client import CloudFrontClient
from mypy_boto3_cloudfront_keyvaluestore.client import CloudFrontKeyValueStoreClient
from mypy_boto3_cloudhsm.client import CloudHSMClient
from mypy_boto3_cloudhsmv2.client import CloudHSMV2Client
from mypy_boto3_cloudsearch.client import CloudSearchClient
from mypy_boto3_cloudsearchdomain.client import CloudSearchDomainClient
from mypy_boto3_cloudtrail.client import CloudTrailClient
from mypy_boto3_cloudtrail_data.client import CloudTrailDataServiceClient
from mypy_boto3_cloudwatch.client import CloudWatchClient
from mypy_boto3_cloudwatch.service_resource import CloudWatchServiceResource
from mypy_boto3_codeartifact.client import CodeArtifactClient
from mypy_boto3_codebuild.client import CodeBuildClient
from mypy_boto3_codecatalyst.client import CodeCatalystClient
from mypy_boto3_codecommit.client import CodeCommitClient
from mypy_boto3_codeconnections.client import CodeConnectionsClient
from mypy_boto3_codedeploy.client import CodeDeployClient
from mypy_boto3_codeguru_reviewer.client import CodeGuruReviewerClient
from mypy_boto3_codeguru_security.client import CodeGuruSecurityClient
from mypy_boto3_codeguruprofiler.client import CodeGuruProfilerClient
from mypy_boto3_codepipeline.client import CodePipelineClient
from mypy_boto3_codestar_connections.client import CodeStarconnectionsClient
from mypy_boto3_codestar_notifications.client import CodeStarNotificationsClient
from mypy_boto3_cognito_identity.client import CognitoIdentityClient
from mypy_boto3_cognito_idp.client import CognitoIdentityProviderClient
from mypy_boto3_cognito_sync.client import CognitoSyncClient
from mypy_boto3_comprehend.client import ComprehendClient
from mypy_boto3_comprehendmedical.client import ComprehendMedicalClient
from mypy_boto3_compute_optimizer.client import ComputeOptimizerClient
from mypy_boto3_config.client import ConfigServiceClient
from mypy_boto3_connect.client import ConnectClient
from mypy_boto3_connect_contact_lens.client import ConnectContactLensClient
from mypy_boto3_connectcampaigns.client import ConnectCampaignServiceClient
from mypy_boto3_connectcampaignsv2.client import ConnectCampaignServiceV2Client
from mypy_boto3_connectcases.client import ConnectCasesClient
from mypy_boto3_connectparticipant.client import ConnectParticipantClient
from mypy_boto3_controlcatalog.client import ControlCatalogClient
from mypy_boto3_controltower.client import ControlTowerClient
from mypy_boto3_cost_optimization_hub.client import CostOptimizationHubClient
from mypy_boto3_cur.client import CostandUsageReportServiceClient
from mypy_boto3_customer_profiles.client import CustomerProfilesClient
from mypy_boto3_databrew.client import GlueDataBrewClient
from mypy_boto3_dataexchange.client import DataExchangeClient
from mypy_boto3_datapipeline.client import DataPipelineClient
from mypy_boto3_datasync.client import DataSyncClient
from mypy_boto3_datazone.client import DataZoneClient
from mypy_boto3_dax.client import DAXClient
from mypy_boto3_deadline.client import DeadlineCloudClient
from mypy_boto3_detective.client import DetectiveClient
from mypy_boto3_devicefarm.client import DeviceFarmClient
from mypy_boto3_devops_guru.client import DevOpsGuruClient
from mypy_boto3_directconnect.client import DirectConnectClient
from mypy_boto3_discovery.client import ApplicationDiscoveryServiceClient
from mypy_boto3_dlm.client import DLMClient
from mypy_boto3_dms.client import DatabaseMigrationServiceClient
from mypy_boto3_docdb.client import DocDBClient
from mypy_boto3_docdb_elastic.client import DocDBElasticClient
from mypy_boto3_drs.client import DrsClient
from mypy_boto3_ds.client import DirectoryServiceClient
from mypy_boto3_ds_data.client import DirectoryServiceDataClient
from mypy_boto3_dsql.client import AuroraDSQLClient
from mypy_boto3_dynamodb.client import DynamoDBClient
from mypy_boto3_dynamodb.service_resource import DynamoDBServiceResource
from mypy_boto3_dynamodbstreams.client import DynamoDBStreamsClient
from mypy_boto3_ebs.client import EBSClient
from mypy_boto3_ec2.client import EC2Client
from mypy_boto3_ec2.service_resource import EC2ServiceResource
from mypy_boto3_ec2_instance_connect.client import EC2InstanceConnectClient
from mypy_boto3_ecr.client import ECRClient
from mypy_boto3_ecr_public.client import ECRPublicClient
from mypy_boto3_ecs.client import ECSClient
from mypy_boto3_efs.client import EFSClient
from mypy_boto3_eks.client import EKSClient
from mypy_boto3_eks_auth.client import EKSAuthClient
from mypy_boto3_elasticache.client import ElastiCacheClient
from mypy_boto3_elasticbeanstalk.client import ElasticBeanstalkClient
from mypy_boto3_elastictranscoder.client import ElasticTranscoderClient
from mypy_boto3_elb.client import ElasticLoadBalancingClient
from mypy_boto3_elbv2.client import ElasticLoadBalancingv2Client
from mypy_boto3_emr.client import EMRClient
from mypy_boto3_emr_containers.client import EMRContainersClient
from mypy_boto3_emr_serverless.client import EMRServerlessClient
from mypy_boto3_entityresolution.client import EntityResolutionClient
from mypy_boto3_es.client import ElasticsearchServiceClient
from mypy_boto3_events.client import EventBridgeClient
from mypy_boto3_evidently.client import CloudWatchEvidentlyClient
from mypy_boto3_finspace.client import FinspaceClient
from mypy_boto3_finspace_data.client import FinSpaceDataClient
from mypy_boto3_firehose.client import FirehoseClient
from mypy_boto3_fis.client import FISClient
from mypy_boto3_fms.client import FMSClient
from mypy_boto3_forecast.client import ForecastServiceClient
from mypy_boto3_forecastquery.client import ForecastQueryServiceClient
from mypy_boto3_frauddetector.client import FraudDetectorClient
from mypy_boto3_freetier.client import FreeTierClient
from mypy_boto3_fsx.client import FSxClient
from mypy_boto3_gamelift.client import GameLiftClient
from mypy_boto3_gameliftstreams.client import GameLiftStreamsClient
from mypy_boto3_geo_maps.client import LocationServiceMapsV2Client
from mypy_boto3_geo_places.client import LocationServicePlacesV2Client
from mypy_boto3_geo_routes.client import LocationServiceRoutesV2Client
from mypy_boto3_glacier.client import GlacierClient
from mypy_boto3_glacier.service_resource import GlacierServiceResource
from mypy_boto3_globalaccelerator.client import GlobalAcceleratorClient
from mypy_boto3_glue.client import GlueClient
from mypy_boto3_grafana.client import ManagedGrafanaClient
from mypy_boto3_greengrass.client import GreengrassClient
from mypy_boto3_greengrassv2.client import GreengrassV2Client
from mypy_boto3_groundstation.client import GroundStationClient
from mypy_boto3_guardduty.client import GuardDutyClient
from mypy_boto3_health.client import HealthClient
from mypy_boto3_healthlake.client import HealthLakeClient
from mypy_boto3_iam.client import IAMClient
from mypy_boto3_iam.service_resource import IAMServiceResource
from mypy_boto3_identitystore.client import IdentityStoreClient
from mypy_boto3_imagebuilder.client import ImagebuilderClient
from mypy_boto3_importexport.client import ImportExportClient
from mypy_boto3_inspector.client import InspectorClient
from mypy_boto3_inspector2.client import Inspector2Client
from mypy_boto3_inspector_scan.client import InspectorscanClient
from mypy_boto3_internetmonitor.client import CloudWatchInternetMonitorClient
from mypy_boto3_invoicing.client import InvoicingClient
from mypy_boto3_iot.client import IoTClient
from mypy_boto3_iot_data.client import IoTDataPlaneClient
from mypy_boto3_iot_jobs_data.client import IoTJobsDataPlaneClient
from mypy_boto3_iot_managed_integrations.client import (
    ManagedintegrationsforIoTDeviceManagementClient,
)
from mypy_boto3_iotanalytics.client import IoTAnalyticsClient
from mypy_boto3_iotdeviceadvisor.client import IoTDeviceAdvisorClient
from mypy_boto3_iotevents.client import IoTEventsClient
from mypy_boto3_iotevents_data.client import IoTEventsDataClient
from mypy_boto3_iotfleethub.client import IoTFleetHubClient
from mypy_boto3_iotfleetwise.client import IoTFleetWiseClient
from mypy_boto3_iotsecuretunneling.client import IoTSecureTunnelingClient
from mypy_boto3_iotsitewise.client import IoTSiteWiseClient
from mypy_boto3_iotthingsgraph.client import IoTThingsGraphClient
from mypy_boto3_iottwinmaker.client import IoTTwinMakerClient
from mypy_boto3_iotwireless.client import IoTWirelessClient
from mypy_boto3_ivs.client import IVSClient
from mypy_boto3_ivs_realtime.client import IvsrealtimeClient
from mypy_boto3_ivschat.client import IvschatClient
from mypy_boto3_kafka.client import KafkaClient
from mypy_boto3_kafkaconnect.client import KafkaConnectClient
from mypy_boto3_kendra.client import KendraClient
from mypy_boto3_kendra_ranking.client import KendraRankingClient
from mypy_boto3_keyspaces.client import KeyspacesClient
from mypy_boto3_kinesis.client import KinesisClient
from mypy_boto3_kinesis_video_archived_media.client import KinesisVideoArchivedMediaClient
from mypy_boto3_kinesis_video_media.client import KinesisVideoMediaClient
from mypy_boto3_kinesis_video_signaling.client import KinesisVideoSignalingChannelsClient
from mypy_boto3_kinesis_video_webrtc_storage.client import KinesisVideoWebRTCStorageClient
from mypy_boto3_kinesisanalytics.client import KinesisAnalyticsClient
from mypy_boto3_kinesisanalyticsv2.client import KinesisAnalyticsV2Client
from mypy_boto3_kinesisvideo.client import KinesisVideoClient
from mypy_boto3_kms.client import KMSClient
from mypy_boto3_lakeformation.client import LakeFormationClient
from mypy_boto3_lambda.client import LambdaClient
from mypy_boto3_launch_wizard.client import LaunchWizardClient
from mypy_boto3_lex_models.client import LexModelBuildingServiceClient
from mypy_boto3_lex_runtime.client import LexRuntimeServiceClient
from mypy_boto3_lexv2_models.client import LexModelsV2Client
from mypy_boto3_lexv2_runtime.client import LexRuntimeV2Client
from mypy_boto3_license_manager.client import LicenseManagerClient
from mypy_boto3_license_manager_linux_subscriptions.client import (
    LicenseManagerLinuxSubscriptionsClient,
)
from mypy_boto3_license_manager_user_subscriptions.client import (
    LicenseManagerUserSubscriptionsClient,
)
from mypy_boto3_lightsail.client import LightsailClient
from mypy_boto3_location.client import LocationServiceClient
from mypy_boto3_logs.client import CloudWatchLogsClient
from mypy_boto3_lookoutequipment.client import LookoutEquipmentClient
from mypy_boto3_lookoutmetrics.client import LookoutMetricsClient
from mypy_boto3_lookoutvision.client import LookoutforVisionClient
from mypy_boto3_m2.client import MainframeModernizationClient
from mypy_boto3_machinelearning.client import MachineLearningClient
from mypy_boto3_macie2.client import Macie2Client
from mypy_boto3_mailmanager.client import MailManagerClient
from mypy_boto3_managedblockchain.client import ManagedBlockchainClient
from mypy_boto3_managedblockchain_query.client import ManagedBlockchainQueryClient
from mypy_boto3_marketplace_agreement.client import AgreementServiceClient
from mypy_boto3_marketplace_catalog.client import MarketplaceCatalogClient
from mypy_boto3_marketplace_deployment.client import MarketplaceDeploymentServiceClient
from mypy_boto3_marketplace_entitlement.client import MarketplaceEntitlementServiceClient
from mypy_boto3_marketplace_reporting.client import MarketplaceReportingServiceClient
from mypy_boto3_marketplacecommerceanalytics.client import MarketplaceCommerceAnalyticsClient
from mypy_boto3_mediaconnect.client import MediaConnectClient
from mypy_boto3_mediaconvert.client import MediaConvertClient
from mypy_boto3_medialive.client import MediaLiveClient
from mypy_boto3_mediapackage.client import MediaPackageClient
from mypy_boto3_mediapackage_vod.client import MediaPackageVodClient
from mypy_boto3_mediapackagev2.client import Mediapackagev2Client
from mypy_boto3_mediastore.client import MediaStoreClient
from mypy_boto3_mediastore_data.client import MediaStoreDataClient
from mypy_boto3_mediatailor.client import MediaTailorClient
from mypy_boto3_medical_imaging.client import HealthImagingClient
from mypy_boto3_memorydb.client import MemoryDBClient
from mypy_boto3_meteringmarketplace.client import MarketplaceMeteringClient
from mypy_boto3_mgh.client import MigrationHubClient
from mypy_boto3_mgn.client import MgnClient
from mypy_boto3_migration_hub_refactor_spaces.client import MigrationHubRefactorSpacesClient
from mypy_boto3_migrationhub_config.client import MigrationHubConfigClient
from mypy_boto3_migrationhuborchestrator.client import MigrationHubOrchestratorClient
from mypy_boto3_migrationhubstrategy.client import MigrationHubStrategyRecommendationsClient
from mypy_boto3_mq.client import MQClient
from mypy_boto3_mturk.client import MTurkClient
from mypy_boto3_mwaa.client import MWAAClient
from mypy_boto3_neptune.client import NeptuneClient
from mypy_boto3_neptune_graph.client import NeptuneGraphClient
from mypy_boto3_neptunedata.client import NeptuneDataClient
from mypy_boto3_network_firewall.client import NetworkFirewallClient
from mypy_boto3_networkflowmonitor.client import NetworkFlowMonitorClient
from mypy_boto3_networkmanager.client import NetworkManagerClient
from mypy_boto3_networkmonitor.client import CloudWatchNetworkMonitorClient
from mypy_boto3_notifications.client import UserNotificationsClient
from mypy_boto3_notificationscontacts.client import UserNotificationsContactsClient
from mypy_boto3_oam.client import CloudWatchObservabilityAccessManagerClient
from mypy_boto3_observabilityadmin.client import CloudWatchObservabilityAdminServiceClient
from mypy_boto3_omics.client import OmicsClient
from mypy_boto3_opensearch.client import OpenSearchServiceClient
from mypy_boto3_opensearchserverless.client import OpenSearchServiceServerlessClient
from mypy_boto3_opsworks.client import OpsWorksClient
from mypy_boto3_opsworks.service_resource import OpsWorksServiceResource
from mypy_boto3_opsworkscm.client import OpsWorksCMClient
from mypy_boto3_organizations.client import OrganizationsClient
from mypy_boto3_osis.client import OpenSearchIngestionClient
from mypy_boto3_outposts.client import OutpostsClient
from mypy_boto3_panorama.client import PanoramaClient
from mypy_boto3_partnercentral_selling.client import PartnerCentralSellingAPIClient
from mypy_boto3_payment_cryptography.client import PaymentCryptographyControlPlaneClient
from mypy_boto3_payment_cryptography_data.client import PaymentCryptographyDataPlaneClient
from mypy_boto3_pca_connector_ad.client import PcaConnectorAdClient
from mypy_boto3_pca_connector_scep.client import PrivateCAConnectorforSCEPClient
from mypy_boto3_pcs.client import ParallelComputingServiceClient
from mypy_boto3_personalize.client import PersonalizeClient
from mypy_boto3_personalize_events.client import PersonalizeEventsClient
from mypy_boto3_personalize_runtime.client import PersonalizeRuntimeClient
from mypy_boto3_pi.client import PIClient
from mypy_boto3_pinpoint.client import PinpointClient
from mypy_boto3_pinpoint_email.client import PinpointEmailClient
from mypy_boto3_pinpoint_sms_voice.client import PinpointSMSVoiceClient
from mypy_boto3_pinpoint_sms_voice_v2.client import PinpointSMSVoiceV2Client
from mypy_boto3_pipes.client import EventBridgePipesClient
from mypy_boto3_polly.client import PollyClient
from mypy_boto3_pricing.client import PricingClient
from mypy_boto3_privatenetworks.client import Private5GClient
from mypy_boto3_proton.client import ProtonClient
from mypy_boto3_qapps.client import QAppsClient
from mypy_boto3_qbusiness.client import QBusinessClient
from mypy_boto3_qconnect.client import QConnectClient
from mypy_boto3_qldb.client import QLDBClient
from mypy_boto3_qldb_session.client import QLDBSessionClient
from mypy_boto3_quicksight.client import QuickSightClient
from mypy_boto3_ram.client import RAMClient
from mypy_boto3_rbin.client import RecycleBinClient
from mypy_boto3_rds.client import RDSClient
from mypy_boto3_rds_data.client import RDSDataServiceClient
from mypy_boto3_redshift.client import RedshiftClient
from mypy_boto3_redshift_data.client import RedshiftDataAPIServiceClient
from mypy_boto3_redshift_serverless.client import RedshiftServerlessClient
from mypy_boto3_rekognition.client import RekognitionClient
from mypy_boto3_repostspace.client import RePostPrivateClient
from mypy_boto3_resiliencehub.client import ResilienceHubClient
from mypy_boto3_resource_explorer_2.client import ResourceExplorerClient
from mypy_boto3_resource_groups.client import ResourceGroupsClient
from mypy_boto3_resourcegroupstaggingapi.client import ResourceGroupsTaggingAPIClient
from mypy_boto3_robomaker.client import RoboMakerClient
from mypy_boto3_rolesanywhere.client import IAMRolesAnywhereClient
from mypy_boto3_route53.client import Route53Client
from mypy_boto3_route53_recovery_cluster.client import Route53RecoveryClusterClient
from mypy_boto3_route53_recovery_control_config.client import Route53RecoveryControlConfigClient
from mypy_boto3_route53_recovery_readiness.client import Route53RecoveryReadinessClient
from mypy_boto3_route53domains.client import Route53DomainsClient
from mypy_boto3_route53profiles.client import Route53ProfilesClient
from mypy_boto3_route53resolver.client import Route53ResolverClient
from mypy_boto3_rum.client import CloudWatchRUMClient
from mypy_boto3_s3.client import S3Client
from mypy_boto3_s3.service_resource import S3ServiceResource
from mypy_boto3_s3control.client import S3ControlClient
from mypy_boto3_s3outposts.client import S3OutpostsClient
from mypy_boto3_s3tables.client import S3TablesClient
from mypy_boto3_sagemaker.client import SageMakerClient
from mypy_boto3_sagemaker_a2i_runtime.client import AugmentedAIRuntimeClient
from mypy_boto3_sagemaker_edge.client import SagemakerEdgeManagerClient
from mypy_boto3_sagemaker_featurestore_runtime.client import SageMakerFeatureStoreRuntimeClient
from mypy_boto3_sagemaker_geospatial.client import SageMakergeospatialcapabilitiesClient
from mypy_boto3_sagemaker_metrics.client import SageMakerMetricsClient
from mypy_boto3_sagemaker_runtime.client import SageMakerRuntimeClient
from mypy_boto3_savingsplans.client import SavingsPlansClient
from mypy_boto3_scheduler.client import EventBridgeSchedulerClient
from mypy_boto3_schemas.client import SchemasClient
from mypy_boto3_sdb.client import SimpleDBClient
from mypy_boto3_secretsmanager.client import SecretsManagerClient
from mypy_boto3_security_ir.client import SecurityIncidentResponseClient
from mypy_boto3_securityhub.client import SecurityHubClient
from mypy_boto3_securitylake.client import SecurityLakeClient
from mypy_boto3_serverlessrepo.client import ServerlessApplicationRepositoryClient
from mypy_boto3_service_quotas.client import ServiceQuotasClient
from mypy_boto3_servicecatalog.client import ServiceCatalogClient
from mypy_boto3_servicecatalog_appregistry.client import AppRegistryClient
from mypy_boto3_servicediscovery.client import ServiceDiscoveryClient
from mypy_boto3_ses.client import SESClient
from mypy_boto3_sesv2.client import SESV2Client
from mypy_boto3_shield.client import ShieldClient
from mypy_boto3_signer.client import SignerClient
from mypy_boto3_simspaceweaver.client import SimSpaceWeaverClient
from mypy_boto3_sms.client import SMSClient
from mypy_boto3_sms_voice.client import SMSVoiceClient
from mypy_boto3_snow_device_management.client import SnowDeviceManagementClient
from mypy_boto3_snowball.client import SnowballClient
from mypy_boto3_sns.client import SNSClient
from mypy_boto3_sns.service_resource import SNSServiceResource
from mypy_boto3_socialmessaging.client import EndUserMessagingSocialClient
from mypy_boto3_sqs.client import SQSClient
from mypy_boto3_sqs.service_resource import SQSServiceResource
from mypy_boto3_ssm.client import SSMClient
from mypy_boto3_ssm_contacts.client import SSMContactsClient
from mypy_boto3_ssm_incidents.client import SSMIncidentsClient
from mypy_boto3_ssm_quicksetup.client import SystemsManagerQuickSetupClient
from mypy_boto3_ssm_sap.client import SsmSapClient
from mypy_boto3_sso.client import SSOClient
from mypy_boto3_sso_admin.client import SSOAdminClient
from mypy_boto3_sso_oidc.client import SSOOIDCClient
from mypy_boto3_stepfunctions.client import SFNClient
from mypy_boto3_storagegateway.client import StorageGatewayClient
from mypy_boto3_sts.client import STSClient
from mypy_boto3_supplychain.client import SupplyChainClient
from mypy_boto3_support.client import SupportClient
from mypy_boto3_support_app.client import SupportAppClient
from mypy_boto3_swf.client import SWFClient
from mypy_boto3_synthetics.client import SyntheticsClient
from mypy_boto3_taxsettings.client import TaxSettingsClient
from mypy_boto3_textract.client import TextractClient
from mypy_boto3_timestream_influxdb.client import TimestreamInfluxDBClient
from mypy_boto3_timestream_query.client import TimestreamQueryClient
from mypy_boto3_timestream_write.client import TimestreamWriteClient
from mypy_boto3_tnb.client import TelcoNetworkBuilderClient
from mypy_boto3_transcribe.client import TranscribeServiceClient
from mypy_boto3_transfer.client import TransferClient
from mypy_boto3_translate.client import TranslateClient
from mypy_boto3_trustedadvisor.client import TrustedAdvisorPublicAPIClient
from mypy_boto3_verifiedpermissions.client import VerifiedPermissionsClient
from mypy_boto3_voice_id.client import VoiceIDClient
from mypy_boto3_vpc_lattice.client import VPCLatticeClient
from mypy_boto3_waf.client import WAFClient
from mypy_boto3_waf_regional.client import WAFRegionalClient
from mypy_boto3_wafv2.client import WAFV2Client
from mypy_boto3_wellarchitected.client import WellArchitectedClient
from mypy_boto3_wisdom.client import ConnectWisdomServiceClient
from mypy_boto3_workdocs.client import WorkDocsClient
from mypy_boto3_workmail.client import WorkMailClient
from mypy_boto3_workmailmessageflow.client import WorkMailMessageFlowClient
from mypy_boto3_workspaces.client import WorkSpacesClient
from mypy_boto3_workspaces_thin_client.client import WorkSpacesThinClientClient
from mypy_boto3_workspaces_web.client import WorkSpacesWebClient
from mypy_boto3_xray.client import XRayClient

if sys.version_info >= (3, 12):
    from typing import Literal
else:
    from typing_extensions import Literal

class Session:
    def __init__(
        self,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        region_name: str | None = ...,
        botocore_session: BotocoreSession | None = ...,
        profile_name: str | None = ...,
        aws_account_id: str | None = ...,
    ) -> None:
        self._session: BotocoreSession
        self.resource_factory: ResourceFactory
        self._loader: Loader

    def __repr__(self) -> str: ...
    @property
    def profile_name(self) -> str: ...
    @property
    def region_name(self) -> str: ...
    @property
    def events(self) -> BaseEventHooks: ...
    @property
    def available_profiles(self) -> list[str]: ...
    def _setup_loader(self) -> None: ...
    def get_available_services(self) -> list[str]: ...
    def get_available_resources(self) -> list[str]: ...
    def get_available_partitions(self) -> list[str]: ...
    def get_available_regions(
        self,
        service_name: str,
        partition_name: str = ...,
        allow_non_regional: bool = ...,
    ) -> list[str]: ...
    def get_credentials(self) -> Credentials | None: ...
    def get_partition_for_region(self, region_name: str) -> str: ...
    @overload
    def client(
        self,
        service_name: Literal["accessanalyzer"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> AccessAnalyzerClient:
        """
        Create client for AccessAnalyzer service.
        """

    @overload
    def client(
        self,
        service_name: Literal["account"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> AccountClient:
        """
        Create client for Account service.
        """

    @overload
    def client(
        self,
        service_name: Literal["acm"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> ACMClient:
        """
        Create client for ACM service.
        """

    @overload
    def client(
        self,
        service_name: Literal["acm-pca"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> ACMPCAClient:
        """
        Create client for ACMPCA service.
        """

    @overload
    def client(
        self,
        service_name: Literal["amp"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> PrometheusServiceClient:
        """
        Create client for PrometheusService service.
        """

    @overload
    def client(
        self,
        service_name: Literal["amplify"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> AmplifyClient:
        """
        Create client for Amplify service.
        """

    @overload
    def client(
        self,
        service_name: Literal["amplifybackend"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> AmplifyBackendClient:
        """
        Create client for AmplifyBackend service.
        """

    @overload
    def client(
        self,
        service_name: Literal["amplifyuibuilder"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> AmplifyUIBuilderClient:
        """
        Create client for AmplifyUIBuilder service.
        """

    @overload
    def client(
        self,
        service_name: Literal["apigateway"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> APIGatewayClient:
        """
        Create client for APIGateway service.
        """

    @overload
    def client(
        self,
        service_name: Literal["apigatewaymanagementapi"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> ApiGatewayManagementApiClient:
        """
        Create client for ApiGatewayManagementApi service.
        """

    @overload
    def client(
        self,
        service_name: Literal["apigatewayv2"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> ApiGatewayV2Client:
        """
        Create client for ApiGatewayV2 service.
        """

    @overload
    def client(
        self,
        service_name: Literal["appconfig"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> AppConfigClient:
        """
        Create client for AppConfig service.
        """

    @overload
    def client(
        self,
        service_name: Literal["appconfigdata"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> AppConfigDataClient:
        """
        Create client for AppConfigData service.
        """

    @overload
    def client(
        self,
        service_name: Literal["appfabric"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> AppFabricClient:
        """
        Create client for AppFabric service.
        """

    @overload
    def client(
        self,
        service_name: Literal["appflow"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> AppflowClient:
        """
        Create client for Appflow service.
        """

    @overload
    def client(
        self,
        service_name: Literal["appintegrations"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> AppIntegrationsServiceClient:
        """
        Create client for AppIntegrationsService service.
        """

    @overload
    def client(
        self,
        service_name: Literal["application-autoscaling"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> ApplicationAutoScalingClient:
        """
        Create client for ApplicationAutoScaling service.
        """

    @overload
    def client(
        self,
        service_name: Literal["application-insights"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> ApplicationInsightsClient:
        """
        Create client for ApplicationInsights service.
        """

    @overload
    def client(
        self,
        service_name: Literal["application-signals"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> CloudWatchApplicationSignalsClient:
        """
        Create client for CloudWatchApplicationSignals service.
        """

    @overload
    def client(
        self,
        service_name: Literal["applicationcostprofiler"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> ApplicationCostProfilerClient:
        """
        Create client for ApplicationCostProfiler service.
        """

    @overload
    def client(
        self,
        service_name: Literal["appmesh"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> AppMeshClient:
        """
        Create client for AppMesh service.
        """

    @overload
    def client(
        self,
        service_name: Literal["apprunner"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> AppRunnerClient:
        """
        Create client for AppRunner service.
        """

    @overload
    def client(
        self,
        service_name: Literal["appstream"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> AppStreamClient:
        """
        Create client for AppStream service.
        """

    @overload
    def client(
        self,
        service_name: Literal["appsync"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> AppSyncClient:
        """
        Create client for AppSync service.
        """

    @overload
    def client(
        self,
        service_name: Literal["apptest"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> MainframeModernizationApplicationTestingClient:
        """
        Create client for MainframeModernizationApplicationTesting service.
        """

    @overload
    def client(
        self,
        service_name: Literal["arc-zonal-shift"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> ARCZonalShiftClient:
        """
        Create client for ARCZonalShift service.
        """

    @overload
    def client(
        self,
        service_name: Literal["artifact"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> ArtifactClient:
        """
        Create client for Artifact service.
        """

    @overload
    def client(
        self,
        service_name: Literal["athena"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> AthenaClient:
        """
        Create client for Athena service.
        """

    @overload
    def client(
        self,
        service_name: Literal["auditmanager"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> AuditManagerClient:
        """
        Create client for AuditManager service.
        """

    @overload
    def client(
        self,
        service_name: Literal["autoscaling"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> AutoScalingClient:
        """
        Create client for AutoScaling service.
        """

    @overload
    def client(
        self,
        service_name: Literal["autoscaling-plans"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> AutoScalingPlansClient:
        """
        Create client for AutoScalingPlans service.
        """

    @overload
    def client(
        self,
        service_name: Literal["b2bi"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> B2BIClient:
        """
        Create client for B2BI service.
        """

    @overload
    def client(
        self,
        service_name: Literal["backup"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> BackupClient:
        """
        Create client for Backup service.
        """

    @overload
    def client(
        self,
        service_name: Literal["backup-gateway"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> BackupGatewayClient:
        """
        Create client for BackupGateway service.
        """

    @overload
    def client(
        self,
        service_name: Literal["backupsearch"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> BackupSearchClient:
        """
        Create client for BackupSearch service.
        """

    @overload
    def client(
        self,
        service_name: Literal["batch"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> BatchClient:
        """
        Create client for Batch service.
        """

    @overload
    def client(
        self,
        service_name: Literal["bcm-data-exports"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> BillingandCostManagementDataExportsClient:
        """
        Create client for BillingandCostManagementDataExports service.
        """

    @overload
    def client(
        self,
        service_name: Literal["bcm-pricing-calculator"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> BillingandCostManagementPricingCalculatorClient:
        """
        Create client for BillingandCostManagementPricingCalculator service.
        """

    @overload
    def client(
        self,
        service_name: Literal["bedrock"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> BedrockClient:
        """
        Create client for Bedrock service.
        """

    @overload
    def client(
        self,
        service_name: Literal["bedrock-agent"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> AgentsforBedrockClient:
        """
        Create client for AgentsforBedrock service.
        """

    @overload
    def client(
        self,
        service_name: Literal["bedrock-agent-runtime"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> AgentsforBedrockRuntimeClient:
        """
        Create client for AgentsforBedrockRuntime service.
        """

    @overload
    def client(
        self,
        service_name: Literal["bedrock-data-automation"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> DataAutomationforBedrockClient:
        """
        Create client for DataAutomationforBedrock service.
        """

    @overload
    def client(
        self,
        service_name: Literal["bedrock-data-automation-runtime"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> RuntimeforBedrockDataAutomationClient:
        """
        Create client for RuntimeforBedrockDataAutomation service.
        """

    @overload
    def client(
        self,
        service_name: Literal["bedrock-runtime"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> BedrockRuntimeClient:
        """
        Create client for BedrockRuntime service.
        """

    @overload
    def client(
        self,
        service_name: Literal["billing"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> BillingClient:
        """
        Create client for Billing service.
        """

    @overload
    def client(
        self,
        service_name: Literal["billingconductor"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> BillingConductorClient:
        """
        Create client for BillingConductor service.
        """

    @overload
    def client(
        self,
        service_name: Literal["braket"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> BraketClient:
        """
        Create client for Braket service.
        """

    @overload
    def client(
        self,
        service_name: Literal["budgets"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> BudgetsClient:
        """
        Create client for Budgets service.
        """

    @overload
    def client(
        self,
        service_name: Literal["ce"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> CostExplorerClient:
        """
        Create client for CostExplorer service.
        """

    @overload
    def client(
        self,
        service_name: Literal["chatbot"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> ChatbotClient:
        """
        Create client for Chatbot service.
        """

    @overload
    def client(
        self,
        service_name: Literal["chime"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> ChimeClient:
        """
        Create client for Chime service.
        """

    @overload
    def client(
        self,
        service_name: Literal["chime-sdk-identity"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> ChimeSDKIdentityClient:
        """
        Create client for ChimeSDKIdentity service.
        """

    @overload
    def client(
        self,
        service_name: Literal["chime-sdk-media-pipelines"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> ChimeSDKMediaPipelinesClient:
        """
        Create client for ChimeSDKMediaPipelines service.
        """

    @overload
    def client(
        self,
        service_name: Literal["chime-sdk-meetings"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> ChimeSDKMeetingsClient:
        """
        Create client for ChimeSDKMeetings service.
        """

    @overload
    def client(
        self,
        service_name: Literal["chime-sdk-messaging"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> ChimeSDKMessagingClient:
        """
        Create client for ChimeSDKMessaging service.
        """

    @overload
    def client(
        self,
        service_name: Literal["chime-sdk-voice"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> ChimeSDKVoiceClient:
        """
        Create client for ChimeSDKVoice service.
        """

    @overload
    def client(
        self,
        service_name: Literal["cleanrooms"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> CleanRoomsServiceClient:
        """
        Create client for CleanRoomsService service.
        """

    @overload
    def client(
        self,
        service_name: Literal["cleanroomsml"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> CleanRoomsMLClient:
        """
        Create client for CleanRoomsML service.
        """

    @overload
    def client(
        self,
        service_name: Literal["cloud9"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> Cloud9Client:
        """
        Create client for Cloud9 service.
        """

    @overload
    def client(
        self,
        service_name: Literal["cloudcontrol"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> CloudControlApiClient:
        """
        Create client for CloudControlApi service.
        """

    @overload
    def client(
        self,
        service_name: Literal["clouddirectory"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> CloudDirectoryClient:
        """
        Create client for CloudDirectory service.
        """

    @overload
    def client(
        self,
        service_name: Literal["cloudformation"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> CloudFormationClient:
        """
        Create client for CloudFormation service.
        """

    @overload
    def client(
        self,
        service_name: Literal["cloudfront"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> CloudFrontClient:
        """
        Create client for CloudFront service.
        """

    @overload
    def client(
        self,
        service_name: Literal["cloudfront-keyvaluestore"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> CloudFrontKeyValueStoreClient:
        """
        Create client for CloudFrontKeyValueStore service.
        """

    @overload
    def client(
        self,
        service_name: Literal["cloudhsm"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> CloudHSMClient:
        """
        Create client for CloudHSM service.
        """

    @overload
    def client(
        self,
        service_name: Literal["cloudhsmv2"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> CloudHSMV2Client:
        """
        Create client for CloudHSMV2 service.
        """

    @overload
    def client(
        self,
        service_name: Literal["cloudsearch"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> CloudSearchClient:
        """
        Create client for CloudSearch service.
        """

    @overload
    def client(
        self,
        service_name: Literal["cloudsearchdomain"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> CloudSearchDomainClient:
        """
        Create client for CloudSearchDomain service.
        """

    @overload
    def client(
        self,
        service_name: Literal["cloudtrail"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> CloudTrailClient:
        """
        Create client for CloudTrail service.
        """

    @overload
    def client(
        self,
        service_name: Literal["cloudtrail-data"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> CloudTrailDataServiceClient:
        """
        Create client for CloudTrailDataService service.
        """

    @overload
    def client(
        self,
        service_name: Literal["cloudwatch"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> CloudWatchClient:
        """
        Create client for CloudWatch service.
        """

    @overload
    def client(
        self,
        service_name: Literal["codeartifact"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> CodeArtifactClient:
        """
        Create client for CodeArtifact service.
        """

    @overload
    def client(
        self,
        service_name: Literal["codebuild"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> CodeBuildClient:
        """
        Create client for CodeBuild service.
        """

    @overload
    def client(
        self,
        service_name: Literal["codecatalyst"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> CodeCatalystClient:
        """
        Create client for CodeCatalyst service.
        """

    @overload
    def client(
        self,
        service_name: Literal["codecommit"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> CodeCommitClient:
        """
        Create client for CodeCommit service.
        """

    @overload
    def client(
        self,
        service_name: Literal["codeconnections"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> CodeConnectionsClient:
        """
        Create client for CodeConnections service.
        """

    @overload
    def client(
        self,
        service_name: Literal["codedeploy"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> CodeDeployClient:
        """
        Create client for CodeDeploy service.
        """

    @overload
    def client(
        self,
        service_name: Literal["codeguru-reviewer"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> CodeGuruReviewerClient:
        """
        Create client for CodeGuruReviewer service.
        """

    @overload
    def client(
        self,
        service_name: Literal["codeguru-security"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> CodeGuruSecurityClient:
        """
        Create client for CodeGuruSecurity service.
        """

    @overload
    def client(
        self,
        service_name: Literal["codeguruprofiler"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> CodeGuruProfilerClient:
        """
        Create client for CodeGuruProfiler service.
        """

    @overload
    def client(
        self,
        service_name: Literal["codepipeline"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> CodePipelineClient:
        """
        Create client for CodePipeline service.
        """

    @overload
    def client(
        self,
        service_name: Literal["codestar-connections"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> CodeStarconnectionsClient:
        """
        Create client for CodeStarconnections service.
        """

    @overload
    def client(
        self,
        service_name: Literal["codestar-notifications"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> CodeStarNotificationsClient:
        """
        Create client for CodeStarNotifications service.
        """

    @overload
    def client(
        self,
        service_name: Literal["cognito-identity"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> CognitoIdentityClient:
        """
        Create client for CognitoIdentity service.
        """

    @overload
    def client(
        self,
        service_name: Literal["cognito-idp"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> CognitoIdentityProviderClient:
        """
        Create client for CognitoIdentityProvider service.
        """

    @overload
    def client(
        self,
        service_name: Literal["cognito-sync"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> CognitoSyncClient:
        """
        Create client for CognitoSync service.
        """

    @overload
    def client(
        self,
        service_name: Literal["comprehend"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> ComprehendClient:
        """
        Create client for Comprehend service.
        """

    @overload
    def client(
        self,
        service_name: Literal["comprehendmedical"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> ComprehendMedicalClient:
        """
        Create client for ComprehendMedical service.
        """

    @overload
    def client(
        self,
        service_name: Literal["compute-optimizer"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> ComputeOptimizerClient:
        """
        Create client for ComputeOptimizer service.
        """

    @overload
    def client(
        self,
        service_name: Literal["config"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> ConfigServiceClient:
        """
        Create client for ConfigService service.
        """

    @overload
    def client(
        self,
        service_name: Literal["connect"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> ConnectClient:
        """
        Create client for Connect service.
        """

    @overload
    def client(
        self,
        service_name: Literal["connect-contact-lens"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> ConnectContactLensClient:
        """
        Create client for ConnectContactLens service.
        """

    @overload
    def client(
        self,
        service_name: Literal["connectcampaigns"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> ConnectCampaignServiceClient:
        """
        Create client for ConnectCampaignService service.
        """

    @overload
    def client(
        self,
        service_name: Literal["connectcampaignsv2"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> ConnectCampaignServiceV2Client:
        """
        Create client for ConnectCampaignServiceV2 service.
        """

    @overload
    def client(
        self,
        service_name: Literal["connectcases"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> ConnectCasesClient:
        """
        Create client for ConnectCases service.
        """

    @overload
    def client(
        self,
        service_name: Literal["connectparticipant"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> ConnectParticipantClient:
        """
        Create client for ConnectParticipant service.
        """

    @overload
    def client(
        self,
        service_name: Literal["controlcatalog"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> ControlCatalogClient:
        """
        Create client for ControlCatalog service.
        """

    @overload
    def client(
        self,
        service_name: Literal["controltower"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> ControlTowerClient:
        """
        Create client for ControlTower service.
        """

    @overload
    def client(
        self,
        service_name: Literal["cost-optimization-hub"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> CostOptimizationHubClient:
        """
        Create client for CostOptimizationHub service.
        """

    @overload
    def client(
        self,
        service_name: Literal["cur"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> CostandUsageReportServiceClient:
        """
        Create client for CostandUsageReportService service.
        """

    @overload
    def client(
        self,
        service_name: Literal["customer-profiles"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> CustomerProfilesClient:
        """
        Create client for CustomerProfiles service.
        """

    @overload
    def client(
        self,
        service_name: Literal["databrew"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> GlueDataBrewClient:
        """
        Create client for GlueDataBrew service.
        """

    @overload
    def client(
        self,
        service_name: Literal["dataexchange"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> DataExchangeClient:
        """
        Create client for DataExchange service.
        """

    @overload
    def client(
        self,
        service_name: Literal["datapipeline"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> DataPipelineClient:
        """
        Create client for DataPipeline service.
        """

    @overload
    def client(
        self,
        service_name: Literal["datasync"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> DataSyncClient:
        """
        Create client for DataSync service.
        """

    @overload
    def client(
        self,
        service_name: Literal["datazone"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> DataZoneClient:
        """
        Create client for DataZone service.
        """

    @overload
    def client(
        self,
        service_name: Literal["dax"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> DAXClient:
        """
        Create client for DAX service.
        """

    @overload
    def client(
        self,
        service_name: Literal["deadline"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> DeadlineCloudClient:
        """
        Create client for DeadlineCloud service.
        """

    @overload
    def client(
        self,
        service_name: Literal["detective"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> DetectiveClient:
        """
        Create client for Detective service.
        """

    @overload
    def client(
        self,
        service_name: Literal["devicefarm"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> DeviceFarmClient:
        """
        Create client for DeviceFarm service.
        """

    @overload
    def client(
        self,
        service_name: Literal["devops-guru"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> DevOpsGuruClient:
        """
        Create client for DevOpsGuru service.
        """

    @overload
    def client(
        self,
        service_name: Literal["directconnect"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> DirectConnectClient:
        """
        Create client for DirectConnect service.
        """

    @overload
    def client(
        self,
        service_name: Literal["discovery"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> ApplicationDiscoveryServiceClient:
        """
        Create client for ApplicationDiscoveryService service.
        """

    @overload
    def client(
        self,
        service_name: Literal["dlm"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> DLMClient:
        """
        Create client for DLM service.
        """

    @overload
    def client(
        self,
        service_name: Literal["dms"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> DatabaseMigrationServiceClient:
        """
        Create client for DatabaseMigrationService service.
        """

    @overload
    def client(
        self,
        service_name: Literal["docdb"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> DocDBClient:
        """
        Create client for DocDB service.
        """

    @overload
    def client(
        self,
        service_name: Literal["docdb-elastic"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> DocDBElasticClient:
        """
        Create client for DocDBElastic service.
        """

    @overload
    def client(
        self,
        service_name: Literal["drs"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> DrsClient:
        """
        Create client for Drs service.
        """

    @overload
    def client(
        self,
        service_name: Literal["ds"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> DirectoryServiceClient:
        """
        Create client for DirectoryService service.
        """

    @overload
    def client(
        self,
        service_name: Literal["ds-data"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> DirectoryServiceDataClient:
        """
        Create client for DirectoryServiceData service.
        """

    @overload
    def client(
        self,
        service_name: Literal["dsql"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> AuroraDSQLClient:
        """
        Create client for AuroraDSQL service.
        """

    @overload
    def client(
        self,
        service_name: Literal["dynamodb"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> DynamoDBClient:
        """
        Create client for DynamoDB service.
        """

    @overload
    def client(
        self,
        service_name: Literal["dynamodbstreams"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> DynamoDBStreamsClient:
        """
        Create client for DynamoDBStreams service.
        """

    @overload
    def client(
        self,
        service_name: Literal["ebs"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> EBSClient:
        """
        Create client for EBS service.
        """

    @overload
    def client(
        self,
        service_name: Literal["ec2"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> EC2Client:
        """
        Create client for EC2 service.
        """

    @overload
    def client(
        self,
        service_name: Literal["ec2-instance-connect"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> EC2InstanceConnectClient:
        """
        Create client for EC2InstanceConnect service.
        """

    @overload
    def client(
        self,
        service_name: Literal["ecr"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> ECRClient:
        """
        Create client for ECR service.
        """

    @overload
    def client(
        self,
        service_name: Literal["ecr-public"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> ECRPublicClient:
        """
        Create client for ECRPublic service.
        """

    @overload
    def client(
        self,
        service_name: Literal["ecs"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> ECSClient:
        """
        Create client for ECS service.
        """

    @overload
    def client(
        self,
        service_name: Literal["efs"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> EFSClient:
        """
        Create client for EFS service.
        """

    @overload
    def client(
        self,
        service_name: Literal["eks"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> EKSClient:
        """
        Create client for EKS service.
        """

    @overload
    def client(
        self,
        service_name: Literal["eks-auth"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> EKSAuthClient:
        """
        Create client for EKSAuth service.
        """

    @overload
    def client(
        self,
        service_name: Literal["elasticache"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> ElastiCacheClient:
        """
        Create client for ElastiCache service.
        """

    @overload
    def client(
        self,
        service_name: Literal["elasticbeanstalk"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> ElasticBeanstalkClient:
        """
        Create client for ElasticBeanstalk service.
        """

    @overload
    def client(
        self,
        service_name: Literal["elastictranscoder"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> ElasticTranscoderClient:
        """
        Create client for ElasticTranscoder service.
        """

    @overload
    def client(
        self,
        service_name: Literal["elb"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> ElasticLoadBalancingClient:
        """
        Create client for ElasticLoadBalancing service.
        """

    @overload
    def client(
        self,
        service_name: Literal["elbv2"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> ElasticLoadBalancingv2Client:
        """
        Create client for ElasticLoadBalancingv2 service.
        """

    @overload
    def client(
        self,
        service_name: Literal["emr"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> EMRClient:
        """
        Create client for EMR service.
        """

    @overload
    def client(
        self,
        service_name: Literal["emr-containers"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> EMRContainersClient:
        """
        Create client for EMRContainers service.
        """

    @overload
    def client(
        self,
        service_name: Literal["emr-serverless"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> EMRServerlessClient:
        """
        Create client for EMRServerless service.
        """

    @overload
    def client(
        self,
        service_name: Literal["entityresolution"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> EntityResolutionClient:
        """
        Create client for EntityResolution service.
        """

    @overload
    def client(
        self,
        service_name: Literal["es"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> ElasticsearchServiceClient:
        """
        Create client for ElasticsearchService service.
        """

    @overload
    def client(
        self,
        service_name: Literal["events"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> EventBridgeClient:
        """
        Create client for EventBridge service.
        """

    @overload
    def client(
        self,
        service_name: Literal["evidently"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> CloudWatchEvidentlyClient:
        """
        Create client for CloudWatchEvidently service.
        """

    @overload
    def client(
        self,
        service_name: Literal["finspace"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> FinspaceClient:
        """
        Create client for Finspace service.
        """

    @overload
    def client(
        self,
        service_name: Literal["finspace-data"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> FinSpaceDataClient:
        """
        Create client for FinSpaceData service.
        """

    @overload
    def client(
        self,
        service_name: Literal["firehose"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> FirehoseClient:
        """
        Create client for Firehose service.
        """

    @overload
    def client(
        self,
        service_name: Literal["fis"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> FISClient:
        """
        Create client for FIS service.
        """

    @overload
    def client(
        self,
        service_name: Literal["fms"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> FMSClient:
        """
        Create client for FMS service.
        """

    @overload
    def client(
        self,
        service_name: Literal["forecast"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> ForecastServiceClient:
        """
        Create client for ForecastService service.
        """

    @overload
    def client(
        self,
        service_name: Literal["forecastquery"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> ForecastQueryServiceClient:
        """
        Create client for ForecastQueryService service.
        """

    @overload
    def client(
        self,
        service_name: Literal["frauddetector"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> FraudDetectorClient:
        """
        Create client for FraudDetector service.
        """

    @overload
    def client(
        self,
        service_name: Literal["freetier"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> FreeTierClient:
        """
        Create client for FreeTier service.
        """

    @overload
    def client(
        self,
        service_name: Literal["fsx"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> FSxClient:
        """
        Create client for FSx service.
        """

    @overload
    def client(
        self,
        service_name: Literal["gamelift"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> GameLiftClient:
        """
        Create client for GameLift service.
        """

    @overload
    def client(
        self,
        service_name: Literal["gameliftstreams"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> GameLiftStreamsClient:
        """
        Create client for GameLiftStreams service.
        """

    @overload
    def client(
        self,
        service_name: Literal["geo-maps"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> LocationServiceMapsV2Client:
        """
        Create client for LocationServiceMapsV2 service.
        """

    @overload
    def client(
        self,
        service_name: Literal["geo-places"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> LocationServicePlacesV2Client:
        """
        Create client for LocationServicePlacesV2 service.
        """

    @overload
    def client(
        self,
        service_name: Literal["geo-routes"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> LocationServiceRoutesV2Client:
        """
        Create client for LocationServiceRoutesV2 service.
        """

    @overload
    def client(
        self,
        service_name: Literal["glacier"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> GlacierClient:
        """
        Create client for Glacier service.
        """

    @overload
    def client(
        self,
        service_name: Literal["globalaccelerator"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> GlobalAcceleratorClient:
        """
        Create client for GlobalAccelerator service.
        """

    @overload
    def client(
        self,
        service_name: Literal["glue"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> GlueClient:
        """
        Create client for Glue service.
        """

    @overload
    def client(
        self,
        service_name: Literal["grafana"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> ManagedGrafanaClient:
        """
        Create client for ManagedGrafana service.
        """

    @overload
    def client(
        self,
        service_name: Literal["greengrass"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> GreengrassClient:
        """
        Create client for Greengrass service.
        """

    @overload
    def client(
        self,
        service_name: Literal["greengrassv2"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> GreengrassV2Client:
        """
        Create client for GreengrassV2 service.
        """

    @overload
    def client(
        self,
        service_name: Literal["groundstation"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> GroundStationClient:
        """
        Create client for GroundStation service.
        """

    @overload
    def client(
        self,
        service_name: Literal["guardduty"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> GuardDutyClient:
        """
        Create client for GuardDuty service.
        """

    @overload
    def client(
        self,
        service_name: Literal["health"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> HealthClient:
        """
        Create client for Health service.
        """

    @overload
    def client(
        self,
        service_name: Literal["healthlake"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> HealthLakeClient:
        """
        Create client for HealthLake service.
        """

    @overload
    def client(
        self,
        service_name: Literal["iam"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> IAMClient:
        """
        Create client for IAM service.
        """

    @overload
    def client(
        self,
        service_name: Literal["identitystore"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> IdentityStoreClient:
        """
        Create client for IdentityStore service.
        """

    @overload
    def client(
        self,
        service_name: Literal["imagebuilder"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> ImagebuilderClient:
        """
        Create client for Imagebuilder service.
        """

    @overload
    def client(
        self,
        service_name: Literal["importexport"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> ImportExportClient:
        """
        Create client for ImportExport service.
        """

    @overload
    def client(
        self,
        service_name: Literal["inspector"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> InspectorClient:
        """
        Create client for Inspector service.
        """

    @overload
    def client(
        self,
        service_name: Literal["inspector-scan"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> InspectorscanClient:
        """
        Create client for Inspectorscan service.
        """

    @overload
    def client(
        self,
        service_name: Literal["inspector2"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> Inspector2Client:
        """
        Create client for Inspector2 service.
        """

    @overload
    def client(
        self,
        service_name: Literal["internetmonitor"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> CloudWatchInternetMonitorClient:
        """
        Create client for CloudWatchInternetMonitor service.
        """

    @overload
    def client(
        self,
        service_name: Literal["invoicing"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> InvoicingClient:
        """
        Create client for Invoicing service.
        """

    @overload
    def client(
        self,
        service_name: Literal["iot"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> IoTClient:
        """
        Create client for IoT service.
        """

    @overload
    def client(
        self,
        service_name: Literal["iot-data"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> IoTDataPlaneClient:
        """
        Create client for IoTDataPlane service.
        """

    @overload
    def client(
        self,
        service_name: Literal["iot-jobs-data"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> IoTJobsDataPlaneClient:
        """
        Create client for IoTJobsDataPlane service.
        """

    @overload
    def client(
        self,
        service_name: Literal["iot-managed-integrations"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> ManagedintegrationsforIoTDeviceManagementClient:
        """
        Create client for ManagedintegrationsforIoTDeviceManagement service.
        """

    @overload
    def client(
        self,
        service_name: Literal["iotanalytics"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> IoTAnalyticsClient:
        """
        Create client for IoTAnalytics service.
        """

    @overload
    def client(
        self,
        service_name: Literal["iotdeviceadvisor"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> IoTDeviceAdvisorClient:
        """
        Create client for IoTDeviceAdvisor service.
        """

    @overload
    def client(
        self,
        service_name: Literal["iotevents"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> IoTEventsClient:
        """
        Create client for IoTEvents service.
        """

    @overload
    def client(
        self,
        service_name: Literal["iotevents-data"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> IoTEventsDataClient:
        """
        Create client for IoTEventsData service.
        """

    @overload
    def client(
        self,
        service_name: Literal["iotfleethub"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> IoTFleetHubClient:
        """
        Create client for IoTFleetHub service.
        """

    @overload
    def client(
        self,
        service_name: Literal["iotfleetwise"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> IoTFleetWiseClient:
        """
        Create client for IoTFleetWise service.
        """

    @overload
    def client(
        self,
        service_name: Literal["iotsecuretunneling"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> IoTSecureTunnelingClient:
        """
        Create client for IoTSecureTunneling service.
        """

    @overload
    def client(
        self,
        service_name: Literal["iotsitewise"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> IoTSiteWiseClient:
        """
        Create client for IoTSiteWise service.
        """

    @overload
    def client(
        self,
        service_name: Literal["iotthingsgraph"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> IoTThingsGraphClient:
        """
        Create client for IoTThingsGraph service.
        """

    @overload
    def client(
        self,
        service_name: Literal["iottwinmaker"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> IoTTwinMakerClient:
        """
        Create client for IoTTwinMaker service.
        """

    @overload
    def client(
        self,
        service_name: Literal["iotwireless"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> IoTWirelessClient:
        """
        Create client for IoTWireless service.
        """

    @overload
    def client(
        self,
        service_name: Literal["ivs"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> IVSClient:
        """
        Create client for IVS service.
        """

    @overload
    def client(
        self,
        service_name: Literal["ivs-realtime"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> IvsrealtimeClient:
        """
        Create client for Ivsrealtime service.
        """

    @overload
    def client(
        self,
        service_name: Literal["ivschat"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> IvschatClient:
        """
        Create client for Ivschat service.
        """

    @overload
    def client(
        self,
        service_name: Literal["kafka"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> KafkaClient:
        """
        Create client for Kafka service.
        """

    @overload
    def client(
        self,
        service_name: Literal["kafkaconnect"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> KafkaConnectClient:
        """
        Create client for KafkaConnect service.
        """

    @overload
    def client(
        self,
        service_name: Literal["kendra"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> KendraClient:
        """
        Create client for Kendra service.
        """

    @overload
    def client(
        self,
        service_name: Literal["kendra-ranking"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> KendraRankingClient:
        """
        Create client for KendraRanking service.
        """

    @overload
    def client(
        self,
        service_name: Literal["keyspaces"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> KeyspacesClient:
        """
        Create client for Keyspaces service.
        """

    @overload
    def client(
        self,
        service_name: Literal["kinesis"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> KinesisClient:
        """
        Create client for Kinesis service.
        """

    @overload
    def client(
        self,
        service_name: Literal["kinesis-video-archived-media"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> KinesisVideoArchivedMediaClient:
        """
        Create client for KinesisVideoArchivedMedia service.
        """

    @overload
    def client(
        self,
        service_name: Literal["kinesis-video-media"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> KinesisVideoMediaClient:
        """
        Create client for KinesisVideoMedia service.
        """

    @overload
    def client(
        self,
        service_name: Literal["kinesis-video-signaling"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> KinesisVideoSignalingChannelsClient:
        """
        Create client for KinesisVideoSignalingChannels service.
        """

    @overload
    def client(
        self,
        service_name: Literal["kinesis-video-webrtc-storage"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> KinesisVideoWebRTCStorageClient:
        """
        Create client for KinesisVideoWebRTCStorage service.
        """

    @overload
    def client(
        self,
        service_name: Literal["kinesisanalytics"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> KinesisAnalyticsClient:
        """
        Create client for KinesisAnalytics service.
        """

    @overload
    def client(
        self,
        service_name: Literal["kinesisanalyticsv2"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> KinesisAnalyticsV2Client:
        """
        Create client for KinesisAnalyticsV2 service.
        """

    @overload
    def client(
        self,
        service_name: Literal["kinesisvideo"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> KinesisVideoClient:
        """
        Create client for KinesisVideo service.
        """

    @overload
    def client(
        self,
        service_name: Literal["kms"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> KMSClient:
        """
        Create client for KMS service.
        """

    @overload
    def client(
        self,
        service_name: Literal["lakeformation"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> LakeFormationClient:
        """
        Create client for LakeFormation service.
        """

    @overload
    def client(
        self,
        service_name: Literal["lambda"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> LambdaClient:
        """
        Create client for Lambda service.
        """

    @overload
    def client(
        self,
        service_name: Literal["launch-wizard"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> LaunchWizardClient:
        """
        Create client for LaunchWizard service.
        """

    @overload
    def client(
        self,
        service_name: Literal["lex-models"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> LexModelBuildingServiceClient:
        """
        Create client for LexModelBuildingService service.
        """

    @overload
    def client(
        self,
        service_name: Literal["lex-runtime"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> LexRuntimeServiceClient:
        """
        Create client for LexRuntimeService service.
        """

    @overload
    def client(
        self,
        service_name: Literal["lexv2-models"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> LexModelsV2Client:
        """
        Create client for LexModelsV2 service.
        """

    @overload
    def client(
        self,
        service_name: Literal["lexv2-runtime"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> LexRuntimeV2Client:
        """
        Create client for LexRuntimeV2 service.
        """

    @overload
    def client(
        self,
        service_name: Literal["license-manager"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> LicenseManagerClient:
        """
        Create client for LicenseManager service.
        """

    @overload
    def client(
        self,
        service_name: Literal["license-manager-linux-subscriptions"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> LicenseManagerLinuxSubscriptionsClient:
        """
        Create client for LicenseManagerLinuxSubscriptions service.
        """

    @overload
    def client(
        self,
        service_name: Literal["license-manager-user-subscriptions"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> LicenseManagerUserSubscriptionsClient:
        """
        Create client for LicenseManagerUserSubscriptions service.
        """

    @overload
    def client(
        self,
        service_name: Literal["lightsail"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> LightsailClient:
        """
        Create client for Lightsail service.
        """

    @overload
    def client(
        self,
        service_name: Literal["location"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> LocationServiceClient:
        """
        Create client for LocationService service.
        """

    @overload
    def client(
        self,
        service_name: Literal["logs"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> CloudWatchLogsClient:
        """
        Create client for CloudWatchLogs service.
        """

    @overload
    def client(
        self,
        service_name: Literal["lookoutequipment"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> LookoutEquipmentClient:
        """
        Create client for LookoutEquipment service.
        """

    @overload
    def client(
        self,
        service_name: Literal["lookoutmetrics"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> LookoutMetricsClient:
        """
        Create client for LookoutMetrics service.
        """

    @overload
    def client(
        self,
        service_name: Literal["lookoutvision"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> LookoutforVisionClient:
        """
        Create client for LookoutforVision service.
        """

    @overload
    def client(
        self,
        service_name: Literal["m2"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> MainframeModernizationClient:
        """
        Create client for MainframeModernization service.
        """

    @overload
    def client(
        self,
        service_name: Literal["machinelearning"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> MachineLearningClient:
        """
        Create client for MachineLearning service.
        """

    @overload
    def client(
        self,
        service_name: Literal["macie2"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> Macie2Client:
        """
        Create client for Macie2 service.
        """

    @overload
    def client(
        self,
        service_name: Literal["mailmanager"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> MailManagerClient:
        """
        Create client for MailManager service.
        """

    @overload
    def client(
        self,
        service_name: Literal["managedblockchain"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> ManagedBlockchainClient:
        """
        Create client for ManagedBlockchain service.
        """

    @overload
    def client(
        self,
        service_name: Literal["managedblockchain-query"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> ManagedBlockchainQueryClient:
        """
        Create client for ManagedBlockchainQuery service.
        """

    @overload
    def client(
        self,
        service_name: Literal["marketplace-agreement"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> AgreementServiceClient:
        """
        Create client for AgreementService service.
        """

    @overload
    def client(
        self,
        service_name: Literal["marketplace-catalog"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> MarketplaceCatalogClient:
        """
        Create client for MarketplaceCatalog service.
        """

    @overload
    def client(
        self,
        service_name: Literal["marketplace-deployment"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> MarketplaceDeploymentServiceClient:
        """
        Create client for MarketplaceDeploymentService service.
        """

    @overload
    def client(
        self,
        service_name: Literal["marketplace-entitlement"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> MarketplaceEntitlementServiceClient:
        """
        Create client for MarketplaceEntitlementService service.
        """

    @overload
    def client(
        self,
        service_name: Literal["marketplace-reporting"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> MarketplaceReportingServiceClient:
        """
        Create client for MarketplaceReportingService service.
        """

    @overload
    def client(
        self,
        service_name: Literal["marketplacecommerceanalytics"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> MarketplaceCommerceAnalyticsClient:
        """
        Create client for MarketplaceCommerceAnalytics service.
        """

    @overload
    def client(
        self,
        service_name: Literal["mediaconnect"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> MediaConnectClient:
        """
        Create client for MediaConnect service.
        """

    @overload
    def client(
        self,
        service_name: Literal["mediaconvert"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> MediaConvertClient:
        """
        Create client for MediaConvert service.
        """

    @overload
    def client(
        self,
        service_name: Literal["medialive"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> MediaLiveClient:
        """
        Create client for MediaLive service.
        """

    @overload
    def client(
        self,
        service_name: Literal["mediapackage"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> MediaPackageClient:
        """
        Create client for MediaPackage service.
        """

    @overload
    def client(
        self,
        service_name: Literal["mediapackage-vod"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> MediaPackageVodClient:
        """
        Create client for MediaPackageVod service.
        """

    @overload
    def client(
        self,
        service_name: Literal["mediapackagev2"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> Mediapackagev2Client:
        """
        Create client for Mediapackagev2 service.
        """

    @overload
    def client(
        self,
        service_name: Literal["mediastore"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> MediaStoreClient:
        """
        Create client for MediaStore service.
        """

    @overload
    def client(
        self,
        service_name: Literal["mediastore-data"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> MediaStoreDataClient:
        """
        Create client for MediaStoreData service.
        """

    @overload
    def client(
        self,
        service_name: Literal["mediatailor"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> MediaTailorClient:
        """
        Create client for MediaTailor service.
        """

    @overload
    def client(
        self,
        service_name: Literal["medical-imaging"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> HealthImagingClient:
        """
        Create client for HealthImaging service.
        """

    @overload
    def client(
        self,
        service_name: Literal["memorydb"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> MemoryDBClient:
        """
        Create client for MemoryDB service.
        """

    @overload
    def client(
        self,
        service_name: Literal["meteringmarketplace"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> MarketplaceMeteringClient:
        """
        Create client for MarketplaceMetering service.
        """

    @overload
    def client(
        self,
        service_name: Literal["mgh"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> MigrationHubClient:
        """
        Create client for MigrationHub service.
        """

    @overload
    def client(
        self,
        service_name: Literal["mgn"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> MgnClient:
        """
        Create client for Mgn service.
        """

    @overload
    def client(
        self,
        service_name: Literal["migration-hub-refactor-spaces"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> MigrationHubRefactorSpacesClient:
        """
        Create client for MigrationHubRefactorSpaces service.
        """

    @overload
    def client(
        self,
        service_name: Literal["migrationhub-config"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> MigrationHubConfigClient:
        """
        Create client for MigrationHubConfig service.
        """

    @overload
    def client(
        self,
        service_name: Literal["migrationhuborchestrator"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> MigrationHubOrchestratorClient:
        """
        Create client for MigrationHubOrchestrator service.
        """

    @overload
    def client(
        self,
        service_name: Literal["migrationhubstrategy"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> MigrationHubStrategyRecommendationsClient:
        """
        Create client for MigrationHubStrategyRecommendations service.
        """

    @overload
    def client(
        self,
        service_name: Literal["mq"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> MQClient:
        """
        Create client for MQ service.
        """

    @overload
    def client(
        self,
        service_name: Literal["mturk"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> MTurkClient:
        """
        Create client for MTurk service.
        """

    @overload
    def client(
        self,
        service_name: Literal["mwaa"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> MWAAClient:
        """
        Create client for MWAA service.
        """

    @overload
    def client(
        self,
        service_name: Literal["neptune"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> NeptuneClient:
        """
        Create client for Neptune service.
        """

    @overload
    def client(
        self,
        service_name: Literal["neptune-graph"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> NeptuneGraphClient:
        """
        Create client for NeptuneGraph service.
        """

    @overload
    def client(
        self,
        service_name: Literal["neptunedata"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> NeptuneDataClient:
        """
        Create client for NeptuneData service.
        """

    @overload
    def client(
        self,
        service_name: Literal["network-firewall"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> NetworkFirewallClient:
        """
        Create client for NetworkFirewall service.
        """

    @overload
    def client(
        self,
        service_name: Literal["networkflowmonitor"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> NetworkFlowMonitorClient:
        """
        Create client for NetworkFlowMonitor service.
        """

    @overload
    def client(
        self,
        service_name: Literal["networkmanager"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> NetworkManagerClient:
        """
        Create client for NetworkManager service.
        """

    @overload
    def client(
        self,
        service_name: Literal["networkmonitor"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> CloudWatchNetworkMonitorClient:
        """
        Create client for CloudWatchNetworkMonitor service.
        """

    @overload
    def client(
        self,
        service_name: Literal["notifications"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> UserNotificationsClient:
        """
        Create client for UserNotifications service.
        """

    @overload
    def client(
        self,
        service_name: Literal["notificationscontacts"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> UserNotificationsContactsClient:
        """
        Create client for UserNotificationsContacts service.
        """

    @overload
    def client(
        self,
        service_name: Literal["oam"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> CloudWatchObservabilityAccessManagerClient:
        """
        Create client for CloudWatchObservabilityAccessManager service.
        """

    @overload
    def client(
        self,
        service_name: Literal["observabilityadmin"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> CloudWatchObservabilityAdminServiceClient:
        """
        Create client for CloudWatchObservabilityAdminService service.
        """

    @overload
    def client(
        self,
        service_name: Literal["omics"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> OmicsClient:
        """
        Create client for Omics service.
        """

    @overload
    def client(
        self,
        service_name: Literal["opensearch"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> OpenSearchServiceClient:
        """
        Create client for OpenSearchService service.
        """

    @overload
    def client(
        self,
        service_name: Literal["opensearchserverless"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> OpenSearchServiceServerlessClient:
        """
        Create client for OpenSearchServiceServerless service.
        """

    @overload
    def client(
        self,
        service_name: Literal["opsworks"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> OpsWorksClient:
        """
        Create client for OpsWorks service.
        """

    @overload
    def client(
        self,
        service_name: Literal["opsworkscm"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> OpsWorksCMClient:
        """
        Create client for OpsWorksCM service.
        """

    @overload
    def client(
        self,
        service_name: Literal["organizations"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> OrganizationsClient:
        """
        Create client for Organizations service.
        """

    @overload
    def client(
        self,
        service_name: Literal["osis"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> OpenSearchIngestionClient:
        """
        Create client for OpenSearchIngestion service.
        """

    @overload
    def client(
        self,
        service_name: Literal["outposts"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> OutpostsClient:
        """
        Create client for Outposts service.
        """

    @overload
    def client(
        self,
        service_name: Literal["panorama"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> PanoramaClient:
        """
        Create client for Panorama service.
        """

    @overload
    def client(
        self,
        service_name: Literal["partnercentral-selling"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> PartnerCentralSellingAPIClient:
        """
        Create client for PartnerCentralSellingAPI service.
        """

    @overload
    def client(
        self,
        service_name: Literal["payment-cryptography"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> PaymentCryptographyControlPlaneClient:
        """
        Create client for PaymentCryptographyControlPlane service.
        """

    @overload
    def client(
        self,
        service_name: Literal["payment-cryptography-data"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> PaymentCryptographyDataPlaneClient:
        """
        Create client for PaymentCryptographyDataPlane service.
        """

    @overload
    def client(
        self,
        service_name: Literal["pca-connector-ad"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> PcaConnectorAdClient:
        """
        Create client for PcaConnectorAd service.
        """

    @overload
    def client(
        self,
        service_name: Literal["pca-connector-scep"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> PrivateCAConnectorforSCEPClient:
        """
        Create client for PrivateCAConnectorforSCEP service.
        """

    @overload
    def client(
        self,
        service_name: Literal["pcs"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> ParallelComputingServiceClient:
        """
        Create client for ParallelComputingService service.
        """

    @overload
    def client(
        self,
        service_name: Literal["personalize"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> PersonalizeClient:
        """
        Create client for Personalize service.
        """

    @overload
    def client(
        self,
        service_name: Literal["personalize-events"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> PersonalizeEventsClient:
        """
        Create client for PersonalizeEvents service.
        """

    @overload
    def client(
        self,
        service_name: Literal["personalize-runtime"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> PersonalizeRuntimeClient:
        """
        Create client for PersonalizeRuntime service.
        """

    @overload
    def client(
        self,
        service_name: Literal["pi"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> PIClient:
        """
        Create client for PI service.
        """

    @overload
    def client(
        self,
        service_name: Literal["pinpoint"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> PinpointClient:
        """
        Create client for Pinpoint service.
        """

    @overload
    def client(
        self,
        service_name: Literal["pinpoint-email"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> PinpointEmailClient:
        """
        Create client for PinpointEmail service.
        """

    @overload
    def client(
        self,
        service_name: Literal["pinpoint-sms-voice"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> PinpointSMSVoiceClient:
        """
        Create client for PinpointSMSVoice service.
        """

    @overload
    def client(
        self,
        service_name: Literal["pinpoint-sms-voice-v2"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> PinpointSMSVoiceV2Client:
        """
        Create client for PinpointSMSVoiceV2 service.
        """

    @overload
    def client(
        self,
        service_name: Literal["pipes"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> EventBridgePipesClient:
        """
        Create client for EventBridgePipes service.
        """

    @overload
    def client(
        self,
        service_name: Literal["polly"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> PollyClient:
        """
        Create client for Polly service.
        """

    @overload
    def client(
        self,
        service_name: Literal["pricing"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> PricingClient:
        """
        Create client for Pricing service.
        """

    @overload
    def client(
        self,
        service_name: Literal["privatenetworks"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> Private5GClient:
        """
        Create client for Private5G service.
        """

    @overload
    def client(
        self,
        service_name: Literal["proton"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> ProtonClient:
        """
        Create client for Proton service.
        """

    @overload
    def client(
        self,
        service_name: Literal["qapps"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> QAppsClient:
        """
        Create client for QApps service.
        """

    @overload
    def client(
        self,
        service_name: Literal["qbusiness"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> QBusinessClient:
        """
        Create client for QBusiness service.
        """

    @overload
    def client(
        self,
        service_name: Literal["qconnect"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> QConnectClient:
        """
        Create client for QConnect service.
        """

    @overload
    def client(
        self,
        service_name: Literal["qldb"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> QLDBClient:
        """
        Create client for QLDB service.
        """

    @overload
    def client(
        self,
        service_name: Literal["qldb-session"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> QLDBSessionClient:
        """
        Create client for QLDBSession service.
        """

    @overload
    def client(
        self,
        service_name: Literal["quicksight"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> QuickSightClient:
        """
        Create client for QuickSight service.
        """

    @overload
    def client(
        self,
        service_name: Literal["ram"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> RAMClient:
        """
        Create client for RAM service.
        """

    @overload
    def client(
        self,
        service_name: Literal["rbin"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> RecycleBinClient:
        """
        Create client for RecycleBin service.
        """

    @overload
    def client(
        self,
        service_name: Literal["rds"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> RDSClient:
        """
        Create client for RDS service.
        """

    @overload
    def client(
        self,
        service_name: Literal["rds-data"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> RDSDataServiceClient:
        """
        Create client for RDSDataService service.
        """

    @overload
    def client(
        self,
        service_name: Literal["redshift"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> RedshiftClient:
        """
        Create client for Redshift service.
        """

    @overload
    def client(
        self,
        service_name: Literal["redshift-data"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> RedshiftDataAPIServiceClient:
        """
        Create client for RedshiftDataAPIService service.
        """

    @overload
    def client(
        self,
        service_name: Literal["redshift-serverless"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> RedshiftServerlessClient:
        """
        Create client for RedshiftServerless service.
        """

    @overload
    def client(
        self,
        service_name: Literal["rekognition"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> RekognitionClient:
        """
        Create client for Rekognition service.
        """

    @overload
    def client(
        self,
        service_name: Literal["repostspace"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> RePostPrivateClient:
        """
        Create client for RePostPrivate service.
        """

    @overload
    def client(
        self,
        service_name: Literal["resiliencehub"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> ResilienceHubClient:
        """
        Create client for ResilienceHub service.
        """

    @overload
    def client(
        self,
        service_name: Literal["resource-explorer-2"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> ResourceExplorerClient:
        """
        Create client for ResourceExplorer service.
        """

    @overload
    def client(
        self,
        service_name: Literal["resource-groups"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> ResourceGroupsClient:
        """
        Create client for ResourceGroups service.
        """

    @overload
    def client(
        self,
        service_name: Literal["resourcegroupstaggingapi"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> ResourceGroupsTaggingAPIClient:
        """
        Create client for ResourceGroupsTaggingAPI service.
        """

    @overload
    def client(
        self,
        service_name: Literal["robomaker"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> RoboMakerClient:
        """
        Create client for RoboMaker service.
        """

    @overload
    def client(
        self,
        service_name: Literal["rolesanywhere"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> IAMRolesAnywhereClient:
        """
        Create client for IAMRolesAnywhere service.
        """

    @overload
    def client(
        self,
        service_name: Literal["route53"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> Route53Client:
        """
        Create client for Route53 service.
        """

    @overload
    def client(
        self,
        service_name: Literal["route53-recovery-cluster"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> Route53RecoveryClusterClient:
        """
        Create client for Route53RecoveryCluster service.
        """

    @overload
    def client(
        self,
        service_name: Literal["route53-recovery-control-config"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> Route53RecoveryControlConfigClient:
        """
        Create client for Route53RecoveryControlConfig service.
        """

    @overload
    def client(
        self,
        service_name: Literal["route53-recovery-readiness"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> Route53RecoveryReadinessClient:
        """
        Create client for Route53RecoveryReadiness service.
        """

    @overload
    def client(
        self,
        service_name: Literal["route53domains"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> Route53DomainsClient:
        """
        Create client for Route53Domains service.
        """

    @overload
    def client(
        self,
        service_name: Literal["route53profiles"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> Route53ProfilesClient:
        """
        Create client for Route53Profiles service.
        """

    @overload
    def client(
        self,
        service_name: Literal["route53resolver"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> Route53ResolverClient:
        """
        Create client for Route53Resolver service.
        """

    @overload
    def client(
        self,
        service_name: Literal["rum"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> CloudWatchRUMClient:
        """
        Create client for CloudWatchRUM service.
        """

    @overload
    def client(
        self,
        service_name: Literal["s3"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> S3Client:
        """
        Create client for S3 service.
        """

    @overload
    def client(
        self,
        service_name: Literal["s3control"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> S3ControlClient:
        """
        Create client for S3Control service.
        """

    @overload
    def client(
        self,
        service_name: Literal["s3outposts"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> S3OutpostsClient:
        """
        Create client for S3Outposts service.
        """

    @overload
    def client(
        self,
        service_name: Literal["s3tables"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> S3TablesClient:
        """
        Create client for S3Tables service.
        """

    @overload
    def client(
        self,
        service_name: Literal["sagemaker"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> SageMakerClient:
        """
        Create client for SageMaker service.
        """

    @overload
    def client(
        self,
        service_name: Literal["sagemaker-a2i-runtime"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> AugmentedAIRuntimeClient:
        """
        Create client for AugmentedAIRuntime service.
        """

    @overload
    def client(
        self,
        service_name: Literal["sagemaker-edge"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> SagemakerEdgeManagerClient:
        """
        Create client for SagemakerEdgeManager service.
        """

    @overload
    def client(
        self,
        service_name: Literal["sagemaker-featurestore-runtime"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> SageMakerFeatureStoreRuntimeClient:
        """
        Create client for SageMakerFeatureStoreRuntime service.
        """

    @overload
    def client(
        self,
        service_name: Literal["sagemaker-geospatial"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> SageMakergeospatialcapabilitiesClient:
        """
        Create client for SageMakergeospatialcapabilities service.
        """

    @overload
    def client(
        self,
        service_name: Literal["sagemaker-metrics"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> SageMakerMetricsClient:
        """
        Create client for SageMakerMetrics service.
        """

    @overload
    def client(
        self,
        service_name: Literal["sagemaker-runtime"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> SageMakerRuntimeClient:
        """
        Create client for SageMakerRuntime service.
        """

    @overload
    def client(
        self,
        service_name: Literal["savingsplans"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> SavingsPlansClient:
        """
        Create client for SavingsPlans service.
        """

    @overload
    def client(
        self,
        service_name: Literal["scheduler"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> EventBridgeSchedulerClient:
        """
        Create client for EventBridgeScheduler service.
        """

    @overload
    def client(
        self,
        service_name: Literal["schemas"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> SchemasClient:
        """
        Create client for Schemas service.
        """

    @overload
    def client(
        self,
        service_name: Literal["sdb"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> SimpleDBClient:
        """
        Create client for SimpleDB service.
        """

    @overload
    def client(
        self,
        service_name: Literal["secretsmanager"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> SecretsManagerClient:
        """
        Create client for SecretsManager service.
        """

    @overload
    def client(
        self,
        service_name: Literal["security-ir"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> SecurityIncidentResponseClient:
        """
        Create client for SecurityIncidentResponse service.
        """

    @overload
    def client(
        self,
        service_name: Literal["securityhub"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> SecurityHubClient:
        """
        Create client for SecurityHub service.
        """

    @overload
    def client(
        self,
        service_name: Literal["securitylake"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> SecurityLakeClient:
        """
        Create client for SecurityLake service.
        """

    @overload
    def client(
        self,
        service_name: Literal["serverlessrepo"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> ServerlessApplicationRepositoryClient:
        """
        Create client for ServerlessApplicationRepository service.
        """

    @overload
    def client(
        self,
        service_name: Literal["service-quotas"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> ServiceQuotasClient:
        """
        Create client for ServiceQuotas service.
        """

    @overload
    def client(
        self,
        service_name: Literal["servicecatalog"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> ServiceCatalogClient:
        """
        Create client for ServiceCatalog service.
        """

    @overload
    def client(
        self,
        service_name: Literal["servicecatalog-appregistry"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> AppRegistryClient:
        """
        Create client for AppRegistry service.
        """

    @overload
    def client(
        self,
        service_name: Literal["servicediscovery"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> ServiceDiscoveryClient:
        """
        Create client for ServiceDiscovery service.
        """

    @overload
    def client(
        self,
        service_name: Literal["ses"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> SESClient:
        """
        Create client for SES service.
        """

    @overload
    def client(
        self,
        service_name: Literal["sesv2"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> SESV2Client:
        """
        Create client for SESV2 service.
        """

    @overload
    def client(
        self,
        service_name: Literal["shield"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> ShieldClient:
        """
        Create client for Shield service.
        """

    @overload
    def client(
        self,
        service_name: Literal["signer"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> SignerClient:
        """
        Create client for Signer service.
        """

    @overload
    def client(
        self,
        service_name: Literal["simspaceweaver"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> SimSpaceWeaverClient:
        """
        Create client for SimSpaceWeaver service.
        """

    @overload
    def client(
        self,
        service_name: Literal["sms"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> SMSClient:
        """
        Create client for SMS service.
        """

    @overload
    def client(
        self,
        service_name: Literal["sms-voice"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> SMSVoiceClient:
        """
        Create client for SMSVoice service.
        """

    @overload
    def client(
        self,
        service_name: Literal["snow-device-management"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> SnowDeviceManagementClient:
        """
        Create client for SnowDeviceManagement service.
        """

    @overload
    def client(
        self,
        service_name: Literal["snowball"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> SnowballClient:
        """
        Create client for Snowball service.
        """

    @overload
    def client(
        self,
        service_name: Literal["sns"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> SNSClient:
        """
        Create client for SNS service.
        """

    @overload
    def client(
        self,
        service_name: Literal["socialmessaging"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> EndUserMessagingSocialClient:
        """
        Create client for EndUserMessagingSocial service.
        """

    @overload
    def client(
        self,
        service_name: Literal["sqs"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> SQSClient:
        """
        Create client for SQS service.
        """

    @overload
    def client(
        self,
        service_name: Literal["ssm"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> SSMClient:
        """
        Create client for SSM service.
        """

    @overload
    def client(
        self,
        service_name: Literal["ssm-contacts"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> SSMContactsClient:
        """
        Create client for SSMContacts service.
        """

    @overload
    def client(
        self,
        service_name: Literal["ssm-incidents"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> SSMIncidentsClient:
        """
        Create client for SSMIncidents service.
        """

    @overload
    def client(
        self,
        service_name: Literal["ssm-quicksetup"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> SystemsManagerQuickSetupClient:
        """
        Create client for SystemsManagerQuickSetup service.
        """

    @overload
    def client(
        self,
        service_name: Literal["ssm-sap"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> SsmSapClient:
        """
        Create client for SsmSap service.
        """

    @overload
    def client(
        self,
        service_name: Literal["sso"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> SSOClient:
        """
        Create client for SSO service.
        """

    @overload
    def client(
        self,
        service_name: Literal["sso-admin"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> SSOAdminClient:
        """
        Create client for SSOAdmin service.
        """

    @overload
    def client(
        self,
        service_name: Literal["sso-oidc"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> SSOOIDCClient:
        """
        Create client for SSOOIDC service.
        """

    @overload
    def client(
        self,
        service_name: Literal["stepfunctions"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> SFNClient:
        """
        Create client for SFN service.
        """

    @overload
    def client(
        self,
        service_name: Literal["storagegateway"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> StorageGatewayClient:
        """
        Create client for StorageGateway service.
        """

    @overload
    def client(
        self,
        service_name: Literal["sts"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> STSClient:
        """
        Create client for STS service.
        """

    @overload
    def client(
        self,
        service_name: Literal["supplychain"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> SupplyChainClient:
        """
        Create client for SupplyChain service.
        """

    @overload
    def client(
        self,
        service_name: Literal["support"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> SupportClient:
        """
        Create client for Support service.
        """

    @overload
    def client(
        self,
        service_name: Literal["support-app"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> SupportAppClient:
        """
        Create client for SupportApp service.
        """

    @overload
    def client(
        self,
        service_name: Literal["swf"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> SWFClient:
        """
        Create client for SWF service.
        """

    @overload
    def client(
        self,
        service_name: Literal["synthetics"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> SyntheticsClient:
        """
        Create client for Synthetics service.
        """

    @overload
    def client(
        self,
        service_name: Literal["taxsettings"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> TaxSettingsClient:
        """
        Create client for TaxSettings service.
        """

    @overload
    def client(
        self,
        service_name: Literal["textract"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> TextractClient:
        """
        Create client for Textract service.
        """

    @overload
    def client(
        self,
        service_name: Literal["timestream-influxdb"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> TimestreamInfluxDBClient:
        """
        Create client for TimestreamInfluxDB service.
        """

    @overload
    def client(
        self,
        service_name: Literal["timestream-query"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> TimestreamQueryClient:
        """
        Create client for TimestreamQuery service.
        """

    @overload
    def client(
        self,
        service_name: Literal["timestream-write"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> TimestreamWriteClient:
        """
        Create client for TimestreamWrite service.
        """

    @overload
    def client(
        self,
        service_name: Literal["tnb"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> TelcoNetworkBuilderClient:
        """
        Create client for TelcoNetworkBuilder service.
        """

    @overload
    def client(
        self,
        service_name: Literal["transcribe"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> TranscribeServiceClient:
        """
        Create client for TranscribeService service.
        """

    @overload
    def client(
        self,
        service_name: Literal["transfer"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> TransferClient:
        """
        Create client for Transfer service.
        """

    @overload
    def client(
        self,
        service_name: Literal["translate"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> TranslateClient:
        """
        Create client for Translate service.
        """

    @overload
    def client(
        self,
        service_name: Literal["trustedadvisor"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> TrustedAdvisorPublicAPIClient:
        """
        Create client for TrustedAdvisorPublicAPI service.
        """

    @overload
    def client(
        self,
        service_name: Literal["verifiedpermissions"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> VerifiedPermissionsClient:
        """
        Create client for VerifiedPermissions service.
        """

    @overload
    def client(
        self,
        service_name: Literal["voice-id"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> VoiceIDClient:
        """
        Create client for VoiceID service.
        """

    @overload
    def client(
        self,
        service_name: Literal["vpc-lattice"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> VPCLatticeClient:
        """
        Create client for VPCLattice service.
        """

    @overload
    def client(
        self,
        service_name: Literal["waf"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> WAFClient:
        """
        Create client for WAF service.
        """

    @overload
    def client(
        self,
        service_name: Literal["waf-regional"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> WAFRegionalClient:
        """
        Create client for WAFRegional service.
        """

    @overload
    def client(
        self,
        service_name: Literal["wafv2"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> WAFV2Client:
        """
        Create client for WAFV2 service.
        """

    @overload
    def client(
        self,
        service_name: Literal["wellarchitected"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> WellArchitectedClient:
        """
        Create client for WellArchitected service.
        """

    @overload
    def client(
        self,
        service_name: Literal["wisdom"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> ConnectWisdomServiceClient:
        """
        Create client for ConnectWisdomService service.
        """

    @overload
    def client(
        self,
        service_name: Literal["workdocs"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> WorkDocsClient:
        """
        Create client for WorkDocs service.
        """

    @overload
    def client(
        self,
        service_name: Literal["workmail"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> WorkMailClient:
        """
        Create client for WorkMail service.
        """

    @overload
    def client(
        self,
        service_name: Literal["workmailmessageflow"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> WorkMailMessageFlowClient:
        """
        Create client for WorkMailMessageFlow service.
        """

    @overload
    def client(
        self,
        service_name: Literal["workspaces"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> WorkSpacesClient:
        """
        Create client for WorkSpaces service.
        """

    @overload
    def client(
        self,
        service_name: Literal["workspaces-thin-client"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> WorkSpacesThinClientClient:
        """
        Create client for WorkSpacesThinClient service.
        """

    @overload
    def client(
        self,
        service_name: Literal["workspaces-web"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> WorkSpacesWebClient:
        """
        Create client for WorkSpacesWeb service.
        """

    @overload
    def client(
        self,
        service_name: Literal["xray"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> XRayClient:
        """
        Create client for XRay service.
        """

    @overload
    def resource(
        self,
        service_name: Literal["cloudformation"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> CloudFormationServiceResource:
        """
        Create ServiceResource for CloudFormation service.
        """

    @overload
    def resource(
        self,
        service_name: Literal["cloudwatch"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> CloudWatchServiceResource:
        """
        Create ServiceResource for CloudWatch service.
        """

    @overload
    def resource(
        self,
        service_name: Literal["dynamodb"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> DynamoDBServiceResource:
        """
        Create ServiceResource for DynamoDB service.
        """

    @overload
    def resource(
        self,
        service_name: Literal["ec2"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> EC2ServiceResource:
        """
        Create ServiceResource for EC2 service.
        """

    @overload
    def resource(
        self,
        service_name: Literal["glacier"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> GlacierServiceResource:
        """
        Create ServiceResource for Glacier service.
        """

    @overload
    def resource(
        self,
        service_name: Literal["iam"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> IAMServiceResource:
        """
        Create ServiceResource for IAM service.
        """

    @overload
    def resource(
        self,
        service_name: Literal["opsworks"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> OpsWorksServiceResource:
        """
        Create ServiceResource for OpsWorks service.
        """

    @overload
    def resource(
        self,
        service_name: Literal["s3"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> S3ServiceResource:
        """
        Create ServiceResource for S3 service.
        """

    @overload
    def resource(
        self,
        service_name: Literal["sns"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> SNSServiceResource:
        """
        Create ServiceResource for SNS service.
        """

    @overload
    def resource(
        self,
        service_name: Literal["sqs"],
        region_name: str | None = ...,
        api_version: str | None = ...,
        use_ssl: bool | None = ...,
        verify: bool | str | None = ...,
        endpoint_url: str | None = ...,
        aws_access_key_id: str | None = ...,
        aws_secret_access_key: str | None = ...,
        aws_session_token: str | None = ...,
        config: Config | None = ...,
        aws_account_id: str | None = ...,
    ) -> SQSServiceResource:
        """
        Create ServiceResource for SQS service.
        """
