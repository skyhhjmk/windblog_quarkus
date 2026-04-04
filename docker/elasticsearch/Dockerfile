FROM docker.elastic.co/elasticsearch/elasticsearch:9.3.2

LABEL maintainer="windblog"
LABEL description="Elasticsearch 9.3.2 with IK Analysis plugin for Chinese text segmentation"

RUN elasticsearch-plugin install --batch https://release.infinilabs.com/analysis-ik/stable/elasticsearch-analysis-ik-9.3.2.zip

EXPOSE 9200
EXPOSE 9300