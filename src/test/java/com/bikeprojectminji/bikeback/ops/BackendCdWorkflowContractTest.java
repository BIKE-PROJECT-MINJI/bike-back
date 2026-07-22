package com.bikeprojectminji.bikeback.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class BackendCdWorkflowContractTest {

    @ParameterizedTest
    @MethodSource("scenarios")
    void executesOfflineWorkflowScenario(String scenario) throws Exception {
        Path harness = Files.createTempFile("backend-cd-contract", ".py");
        Files.writeString(harness, HARNESS);
        try {
            Process process = new ProcessBuilder("python3", harness.toString(), scenario).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes());
            assertThat(process.waitFor()).withFailMessage(output).isZero();
        } finally { Files.deleteIfExists(harness); }
    }

    static Stream<String> scenarios() {
        return Stream.of("config-valid", "config-invalid", "db-render", "deploy-render-success", "deploy-mainpid-rejected", "same-release-transfer-failure", "delayed-old-fence", "candidate-identity-failure", "rollback-ln-failure", "rollback-systemctl-failure", "rollback-previous-digest-failure", "public-recover", "public-mainpid-rejected", "retry-preserves-ancestry", "attempt-ancestry-preserved", "public-attempt-ancestry", "attempt-digest-b-failure-recovers-original", "attempt-digest-b-public-recovers-original", "run-store-symlink-rejected", "intermediate-store-symlink-rejected", "dirfd-symlink-sentinels", "public-lock-symlink-sentinel", "owner-mismatch-rejected", "uid-gid-mode-rejected", "workflow-upload-send-poll", "upload-only-exact-conflict-reconciles", "manifest-negative", "jar-form-canonicalization", "relative-current-jar-deploy-public", "evidence-rendered-stdout", "public-evidence-rendered-stdout", "rollback-failure", "stale-marker-rejected", "candidate-replaced-rejected", "cross-run-rejected", "lock-contention-rejected", "always-evidence", "evidence-absent-ids", "evidence-incoming-failure", "evidence-retrieval-error", "evidence-malformed", "evidence-identity-mismatch", "aws-negative-argv");
    }

    private static final String HARNESS = """
        import base64,hashlib,json,os,pathlib,shutil,subprocess,sys,tempfile,yaml
        scenario=sys.argv[1]; w=yaml.safe_load(pathlib.Path('.github/workflows/backend-cd.yml').read_text())
        steps={s.get('name'):s for s in w['jobs']['deploy']['steps'] if isinstance(s,dict) and s.get('name')}
        def config(deploy_dir='/opt/bike-back',run_id='101',run_attempt='1'):
          return base64.b64encode(json.dumps({'deploy_dir':str(deploy_dir),'service':'bike-back','port':8080,'bucket':'bike-artifacts','sha':'a'*40,'run_id':run_id,'run_attempt':run_attempt,'db_parameter':'/bike/db','health_url':'https://bike.example/health','ready_url':'https://bike.example/ready'}).encode()).decode()
        cfg=config()
        def run(name,wd,env,cfg_value=cfg,ids=('db-1','deploy-1')):
          text=steps[name]['run']
          for a,b in {'${{ steps.config.outputs.encoded }}':cfg_value,'${{ steps.config.outputs.health_url }}':'https://bike.example/health','${{ steps.config.outputs.ready_url }}':'https://bike.example/ready','${{ steps.artifact_upload.outputs.key }}':'bike-back/releases/a/app.jar','${{ steps.artifact_upload.outputs.version_id }}':'version-1','${{ steps.artifact_upload.outputs.digest }}':env['EXPECTED_DIGEST'],'${{ steps.preupload_db_gate.outputs.command_id }}':ids[0],'${{ steps.deploy_ssm.outputs.command_id }}':ids[1],'${{ job.status }}':env.get('JOB_STATUS','success')}.items(): text=text.replace(a,b)
          return subprocess.run(['bash','-euo','pipefail','-c',text],cwd=wd,env=env)
        with tempfile.TemporaryDirectory() as d:
          wd=pathlib.Path(d); bindir=wd/'bin'; bindir.mkdir(); log=wd/'calls.log'
          def fake(n,b):
            p=bindir/n; p.write_text('#!/bin/sh'+chr(10)+'echo "'+n+' $*" >> "'+str(log)+'"'+chr(10)+b+chr(10)); p.chmod(0o755)
          fake('aws','''op="$1:$2"; shift 2 || exit 64
          case "$op" in
            ssm:get-parameter) test "$#" = 7 && test "$1" = --name && test "$2" = /bike/db && test "$3" = --with-decryption && test "$4" = --query && test "$5" = Parameter.Value && test "$6" = --output && test "$7" = text && echo postgres://target;;
            s3api:get-object) test "$#" = 7 && test "$1" = --bucket && test "$2" = bike-artifacts && test "$3" = --key && test -n "$4" && test "$5" = --version-id && test -n "$6" && test -n "$7" || exit 64; test "${S3_FAIL:-0}" != 1 || exit 55; cp "$FAKE_ARTIFACT" "$7"; echo '{}';;
            s3api:put-object) test "$#" = 10 && test "$1" = --bucket && test "$2" = bike-artifacts && test "$3" = --key && test -n "$4" && test "$5" = --body && test -f "$6" && test "$7" = --metadata && test "$8" = "sha256=$EXPECTED_DIGEST" && test "$9" = --if-none-match && test "${10}" = '*' || exit 64; case "${PUT_ERROR:-}" in conflict) echo 'An error occurred (PreconditionFailed) when calling the PutObject operation: conditional request failed' >&2; exit 255;; other-operation) echo 'An error occurred (PreconditionFailed) when calling the HeadObject operation: conditional request failed' >&2; exit 255;; lookalike) echo 'PreconditionFailed but not an AWS PutObject response' >&2; exit 255;; access) echo 'An error occurred (AccessDenied) when calling the PutObject operation:' >&2; exit 255;; validation) echo 'An error occurred (ValidationError) when calling the PutObject operation:' >&2; exit 255;; timeout) echo 'timeout reset' >&2; exit 255;; esac; test "${PUT_FAIL:-0}" != 1 || exit 54; printf '{"VersionId":"version-1"}\\n';;
            s3api:head-object) test "$#" = 4 && test "$1" = --bucket && test "$2" = bike-artifacts && test "$3" = --key && test -n "$4" || exit 64; test "${HEAD_BAD_METADATA:-0}" != 1 || { printf '{"VersionId":"version-1","Metadata":{"sha256":"bad"}}\\n'; exit; }; test "${HEAD_NO_VERSION:-0}" != 1 || { printf '{"Metadata":{"sha256":"%s"}}\\n' "$EXPECTED_DIGEST"; exit; }; printf '{"VersionId":"version-1","Metadata":{"sha256":"%s"}}\\n' "$EXPECTED_DIGEST";;
            ssm:get-command-invocation) test "$1" = --command-id && test "$3" = --instance-id && test "$4" = i-aaaaaaaa || exit 64; if test "$#" = 8; then test "$5" = --query && test "$6" = Status && test "$7" = --output && test "$8" = text && echo Success; elif test "$#" = 4; then case "$2" in db-1) printf '%s\\n' "$FAKE_DB_INVOCATION";; deploy-1) printf '%s\\n' "$FAKE_DEPLOY_INVOCATION";; roll-1) printf '%s\\n' "$FAKE_ROLL_INVOCATION";; *) exit 7;; esac; else exit 64; fi;;
            ssm:send-command) test "$#" = 12 || exit 64; test "$1" = --instance-ids && test "$2" = i-aaaaaaaa && test "$3" = --document-name && test "$4" = AWS-RunShellScript && test "$5" = --comment && test -n "$6" && test "$7" = --parameters && test "$9" = --query && test "${10}" = Command.CommandId && test "${11}" = --output && test "${12}" = text || exit 64; case "$8" in file://ssm-db-gate.json|file://ssm-deploy.json|file://ssm-public-rollback.json) echo command-1;; *) exit 64;; esac;;
            *) exit 64;;
          esac''')
          fake('curl','''case "$CURL_MODE:$*" in
            rollback-fail:*) exit 1;;
            *https://*) n=$(cat "$CURL_COUNT" 2>/dev/null || echo 0); n=$((n+1)); echo "$n" > "$CURL_COUNT"; test "$n" -gt 1;;
            *) printf '{"ok":true}\\n';;
          esac''')
          fake('sleep',':')
          fake('psql','echo "POSTGIS|0"')
          fake('systemctl','case "$1" in show) test "${MAINPID_FAIL:-0}" != 1 || exit 42; echo 4242;; restart) if test "${MUTATE_PREVIOUS_ON_RESTART:-0}" = 1; then printf tampered > "$PREVIOUS_PATH"; exit 43; fi; test "${SYSTEMCTL_FAIL:-0}" != 1;; *) exit 64;; esac')
          fake('stat','test "${STAT_FAIL:-0}" != 1 || exit 43; test "$1" = -c || exit 64; case "$2" in %u) echo "${STAT_UID:-0}";; %g) echo "${STAT_GID:-0}";; %a) echo "${STAT_MODE:-700}";; *) exit 64;; esac')
          fake('flock','test "${FLOCK_FAIL:-0}" != 1')
          fake('ps',':')
          for n in ('readlink','sha256sum','ln'):
            fake(n,('''if test "${LN_FAIL_ON_SECOND:-0}" = 1; then count=$(cat "$LN_COUNT" 2>/dev/null || echo 0); count=$((count+1)); echo "$count" > "$LN_COUNT"; test "$count" != 2 || exit 41; fi; ''' if n=='ln' else '')+'exec "'+shutil.which(n)+'" "$@"')
          identity={'sha':'a'*40,'runId':'101','runAttempt':'1'}; previous='/opt/bike-back/'+'b'*40+'/'+'c'*64+'/app.jar'; candidate='/opt/bike-back/'+'a'*40+'/'+'0'*64+'/app.jar'
          db_evidence=json.dumps({'Status':'Success','StandardOutputContent':json.dumps({**identity,'postgis':'POSTGIS','failedRows':0})})
          deploy_evidence=json.dumps({'Status':'Success','StandardOutputContent':json.dumps({**identity,'digest':'0'*64,'previous':previous,'failedRows':0})})
          roll_evidence=json.dumps({'Status':'Success','StandardOutputContent':json.dumps({**identity,'digest':'0'*64,'candidate':candidate,'previous':previous,'rollbackStatus':0})})
          env={**os.environ,'PATH':str(bindir)+':'+os.environ['PATH'],'BIKE_CD_TEST_ALLOW_UNPRIVILEGED':'1','APP_INSTANCE_ID':'i-aaaaaaaa','DEPLOY_S3_BUCKET':'bike-artifacts','APP_DEPLOY_DIR':'/opt/bike-back','APP_SERVICE_NAME':'bike-back','APP_PORT':'8080','HEALTHCHECK_URL':'https://bike.example/health','TARGET_DB_CREDENTIAL_PARAMETER':'/bike/db','AWS_DEPLOY_ROLE_ARN':'arn','GITHUB_SHA':'a'*40,'GITHUB_RUN_ID':'101','GITHUB_RUN_ATTEMPT':'1','GITHUB_OUTPUT':str(wd/'out'),'EXPECTED_DIGEST':'0'*64,'CURL_MODE':'normal','CURL_COUNT':str(wd/'curl-count'),'FAKE_DB_INVOCATION':db_evidence,'FAKE_DEPLOY_INVOCATION':deploy_evidence,'FAKE_ROLL_INVOCATION':roll_evidence}
          def digest(value): return hashlib.sha256(value).hexdigest()
          def run_path(cdigest,run_id='101',run_attempt='1',deploy=None): return (deploy if deploy is not None else wd/'deploy')/'.ssm-runs'/('a'*40)/cdigest/run_id/run_attempt
          def target():
            deploy=wd/'deploy'; previous=b'previous'; candidate=b'candidate'; psha='b'*40; pdigest=digest(previous); cdigest=digest(candidate)
            previous_path=deploy/psha/pdigest/'app.jar'; previous_path.parent.mkdir(parents=True); deploy.chmod(0o700); previous_path.write_bytes(previous)
            (deploy/'current.jar').symlink_to(previous_path)
            source=wd/'candidate.jar'; source.write_bytes(candidate)
            proc=wd/'cmdline'; proc.write_bytes(b'java\\0-jar\\0'+str(deploy/'current.jar').encode()+b'\\0')
            return deploy,previous_path,source,cdigest,proc
          def render_deploy(deploy,cdigest,run_id='101',run_attempt='1'):
            local={**env,'EXPECTED_DIGEST':cdigest}
            assert run('Render rollback-safe deploy state machine',wd,local,config(deploy,run_id,run_attempt)).returncode==0
            return json.loads((wd/'ssm-deploy.json').read_text())['commands'],local
          def execute(commands,local,deploy,proc,proc_cwd=None):
            script='\\n'.join(commands).replace('"/proc/$main_pid/cmdline"','"'+str(proc)+'"')
            script=script.replace('"/proc/$main_pid/cwd"','"'+str(proc_cwd if proc_cwd is not None else wd)+'"')
            script=script.replace(chr(0), r'\\0')
            return subprocess.run(['bash','-euo','pipefail','-c',script],cwd=wd,env=local,text=True,capture_output=True)
          def public_document(deploy,cdigest,run_id='101',run_attempt='1'):
            local={**env,'EXPECTED_DIGEST':cdigest}
            # Render the outer workflow to obtain the exact SSM document it would send.
            assert run('Verify public health and readiness with compensation',wd,local,config(deploy,run_id,run_attempt)).returncode!=0
            return json.loads((wd/'ssm-public-rollback.json').read_text())['commands'],local
          def evidence_step(local,ids=('db-1','deploy-1'),cfg_value=None):
            (wd/'artifact-upload-manifest.json').write_text(json.dumps({'bucket':'bike-artifacts','key':'bike-back/releases/'+('a'*40)+'/'+local['EXPECTED_DIGEST']+'/app.jar','versionId':'version-1','disposition':'immutable-upload-or-verified-reuse','digest':local['EXPECTED_DIGEST']}))
            return run('Preserve remote and runner evidence and final verdict',wd,local,config() if cfg_value is None else cfg_value,ids)
          if scenario=='config-valid': assert run('Validate and encode deployment configuration',wd,env).returncode==0
          elif scenario=='config-invalid': env['APP_INSTANCE_ID']='i-'+'a'*9; assert run('Validate and encode deployment configuration',wd,env).returncode!=0
          elif scenario=='db-render':
            assert run('Render target DB gate command',wd,env).returncode==0
            commands=json.loads((wd/'ssm-db-gate.json').read_text())['commands']
            assert subprocess.run(['bash','-euo','pipefail','-c','\\n'.join(commands)],cwd=wd,env=env).returncode==0
          elif scenario=='deploy-render-success':
            deploy,previous,source,cdigest,proc=target(); commands,local=render_deploy(deploy,cdigest)
            result=execute(commands,{**local,'FAKE_ARTIFACT':str(source)},deploy,proc); assert result.returncode==0
            run_dir=run_path(cdigest)
            assert (deploy/'current.jar').resolve()==deploy/('a'*40)/cdigest/'app.jar'
            assert (run_dir/'promotion-armed').exists() and (run_dir/'deploy-evidence.json').exists()
          elif scenario=='deploy-mainpid-rejected':
            deploy,previous,source,cdigest,proc=target(); commands,local=render_deploy(deploy,cdigest)
            assert execute(commands,{**local,'FAKE_ARTIFACT':str(source),'MAINPID_FAIL':'1'},deploy,proc).returncode!=0
            assert (deploy/'current.jar').resolve()==previous
            commands,local=render_deploy(deploy,cdigest); proc.write_bytes(b'java\\0-jar\\0'+str(previous).encode()+b'\\0')
            assert execute(commands,{**local,'FAKE_ARTIFACT':str(source)},deploy,proc).returncode!=0
            assert (deploy/'current.jar').resolve()==previous
            proc.unlink(); assert execute(commands,{**local,'FAKE_ARTIFACT':str(source)},deploy,proc).returncode!=0
          elif scenario=='same-release-transfer-failure':
            deploy,previous,source,cdigest,proc=target(); candidate=deploy/('a'*40)/cdigest/'app.jar'; candidate.parent.mkdir(parents=True); candidate.write_bytes(b'candidate'); (deploy/'current.jar').unlink(); (deploy/'current.jar').symlink_to(candidate); before=candidate.read_bytes(); commands,local=render_deploy(deploy,cdigest)
            assert execute(commands,{**local,'FAKE_ARTIFACT':str(source),'S3_FAIL':'1'},deploy,proc).returncode!=0
            assert (deploy/'current.jar').resolve()==candidate and candidate.read_bytes()==before and digest(candidate.read_bytes())==cdigest
          elif scenario=='delayed-old-fence':
            deploy,previous,source,cdigest,proc=target(); (deploy/'.accepted-generation').write_text('102 1'+chr(10)); commands,local=render_deploy(deploy,cdigest)
            assert execute(commands,{**local,'FAKE_ARTIFACT':str(source)},deploy,proc).returncode!=0
            assert (deploy/'current.jar').resolve()==previous and 's3api get-object' not in log.read_text()
          elif scenario=='candidate-identity-failure':
            deploy,previous,source,cdigest,proc=target(); wrong=wd/'wrong.jar'; wrong.write_bytes(b'wrong')
            commands,local=render_deploy(deploy,cdigest)
            result=execute(commands,{**local,'FAKE_ARTIFACT':str(wrong)},deploy,proc); assert result.returncode!=0
            assert (deploy/'current.jar').resolve()==previous
            assert 'rollback_status=0' in (run_path(cdigest)/'rollback-status').read_text()
            failed=json.dumps({'Status':'Failed','StandardOutputContent':result.stdout})
            assert evidence_step({**local,'FAKE_DEPLOY_INVOCATION':failed}).returncode!=0
            assert json.loads((wd/'deployment-evidence/final-verdict.json').read_text())['evidenceValid'] is False
            assert json.loads((wd/'deployment-evidence/deploy-state-machine.json').read_text())['Status']=='Failed'
          elif scenario in ('rollback-ln-failure','rollback-systemctl-failure'):
            deploy,previous,source,cdigest,proc=target(); commands,local=render_deploy(deploy,cdigest)
            failure={'rollback-ln-failure':{'LN_FAIL_ON_SECOND':'1','LN_COUNT':str(wd/'ln-count'),'CURL_MODE':'rollback-fail'},'rollback-systemctl-failure':{'SYSTEMCTL_FAIL':'1'}}[scenario]
            result=execute(commands,{**local,'FAKE_ARTIFACT':str(source),**failure},deploy,proc); assert result.returncode!=0
            status=(run_path(cdigest)/'rollback-status').read_text(); assert 'rollback_status=' in status and 'rollback_status=0' not in status and 'rollback_status=' in result.stdout
            if scenario=='rollback-ln-failure': assert (deploy/'current.jar').resolve()==deploy/('a'*40)/cdigest/'app.jar'
            else: assert (deploy/'current.jar').resolve()==previous
          elif scenario=='rollback-previous-digest-failure':
            deploy,previous,source,cdigest,proc=target(); wrong=wd/'wrong.jar'; wrong.write_bytes(b'wrong'); commands,local=render_deploy(deploy,cdigest)
            result=execute(commands,{**local,'FAKE_ARTIFACT':str(source),'MUTATE_PREVIOUS_ON_RESTART':'1','PREVIOUS_PATH':str(previous)},deploy,proc); assert result.returncode!=0
            status=(run_path(cdigest)/'rollback-status').read_text(); assert 'rollback_status=' in status and 'rollback_status=0' not in status and 'rollback_status=' in result.stdout
            assert (deploy/'current.jar').resolve()==deploy/('a'*40)/cdigest/'app.jar'
            assert digest(previous.read_bytes()) != previous.parent.name
          elif scenario=='public-recover':
            deploy,previous,source,cdigest,proc=target(); commands,local=render_deploy(deploy,cdigest); assert execute(commands,{**local,'FAKE_ARTIFACT':str(source)},deploy,proc).returncode==0
            document,local=public_document(deploy,cdigest); assert execute(document,local,deploy,proc).returncode==0
            assert (deploy/'current.jar').resolve()==previous
            status=json.loads((run_path(cdigest)/'public-rollback-status.json').read_text()); assert status['rollbackStatus']==0 and status['candidate'].endswith('/app.jar')
          elif scenario=='public-mainpid-rejected':
            deploy,previous,source,cdigest,proc=target(); commands,local=render_deploy(deploy,cdigest); assert execute(commands,{**local,'FAKE_ARTIFACT':str(source)},deploy,proc).returncode==0
            document,local=public_document(deploy,cdigest); proc.write_bytes(b'java\\0-jar\\0'+str(previous).encode()+b'\\0')
            assert execute(document,local,deploy,proc).returncode!=0
            assert (deploy/'current.jar').resolve()==deploy/('a'*40)/cdigest/'app.jar'
            assert not (run_path(cdigest)/'public-rollback-status.json').exists()
            proc.unlink(); assert execute(document,local,deploy,proc).returncode!=0
            assert (deploy/'current.jar').resolve()==deploy/('a'*40)/cdigest/'app.jar'
          elif scenario=='retry-preserves-ancestry':
            deploy,previous,source,cdigest,proc=target(); commands,local=render_deploy(deploy,cdigest)
            assert execute(commands,{**local,'FAKE_ARTIFACT':str(source)},deploy,proc).returncode==0
            marker=(run_path(cdigest)/'promotion-armed').read_text()
            assert execute(commands,{**local,'FAKE_ARTIFACT':str(source)},deploy,proc).returncode==0
            assert (run_path(cdigest)/'promotion-armed').read_text()==marker and 'previous='+str(previous) in marker
          elif scenario=='attempt-ancestry-preserved':
            deploy,previous,source,cdigest,proc=target(); first,local=render_deploy(deploy,cdigest)
            assert execute(first,{**local,'FAKE_ARTIFACT':str(source)},deploy,proc).returncode==0
            second,local=render_deploy(deploy,cdigest,'101','2'); wrong=wd/'attempt-two-wrong.jar'; wrong.write_bytes(b'wrong')
            result=execute(second,{**local,'FAKE_ARTIFACT':str(wrong)},deploy,proc)
            assert result.returncode!=0 and (deploy/'current.jar').resolve()==previous
            assert 'previous='+str(previous) in (run_path(cdigest,'101','2')/'promotion-armed').read_text()
          elif scenario=='public-attempt-ancestry':
            deploy,previous,source,cdigest,proc=target(); first,local=render_deploy(deploy,cdigest)
            assert execute(first,{**local,'FAKE_ARTIFACT':str(source)},deploy,proc).returncode==0
            second,local=render_deploy(deploy,cdigest,'101','2'); assert execute(second,{**local,'FAKE_ARTIFACT':str(source)},deploy,proc).returncode==0
            document,local=public_document(deploy,cdigest,'101','2'); assert execute(document,local,deploy,proc).returncode==0
            assert (deploy/'current.jar').resolve()==previous
          elif scenario in ('attempt-digest-b-failure-recovers-original','attempt-digest-b-public-recovers-original'):
            deploy,previous,source,digest_a,proc=target(); first,local=render_deploy(deploy,digest_a); assert execute(first,{**local,'FAKE_ARTIFACT':str(source)},deploy,proc).returncode==0
            source_b=wd/'candidate-b.jar'; source_b.write_bytes(b'candidate-b'); digest_b=digest(source_b.read_bytes()); second,local=render_deploy(deploy,digest_b,'101','2')
            if scenario.endswith('failure-recovers-original'):
              wrong=wd/'wrong-b.jar'; wrong.write_bytes(b'wrong'); assert execute(second,{**local,'FAKE_ARTIFACT':str(wrong)},deploy,proc).returncode!=0
            else:
              assert execute(second,{**local,'FAKE_ARTIFACT':str(source_b)},deploy,proc).returncode==0; document,local=public_document(deploy,digest_b,'101','2'); assert execute(document,local,deploy,proc).returncode==0
            assert (deploy/'current.jar').resolve()==previous
          elif scenario=='run-store-symlink-rejected':
            deploy,previous,source,cdigest,proc=target(); run_path(cdigest).parent.mkdir(parents=True); run_path(cdigest).symlink_to(wd/'elsewhere'); commands,local=render_deploy(deploy,cdigest)
            assert execute(commands,{**local,'FAKE_ARTIFACT':str(source)},deploy,proc).returncode!=0
            assert (deploy/'current.jar').resolve()==previous
          elif scenario=='owner-mismatch-rejected':
            deploy,previous,source,cdigest,proc=target(); commands,local=render_deploy(deploy,cdigest)
            assert execute(commands,{**local,'FAKE_ARTIFACT':str(source),'STAT_FAIL':'1'},deploy,proc).returncode!=0
            assert (deploy/'current.jar').resolve()==previous
          elif scenario=='intermediate-store-symlink-rejected':
            deploy,previous,source,cdigest,proc=target(); (deploy/'.ssm-runs').symlink_to(wd/'outside'); commands,local=render_deploy(deploy,cdigest)
            assert execute(commands,{**local,'FAKE_ARTIFACT':str(source)},deploy,proc).returncode!=0 and (deploy/'current.jar').resolve()==previous
          elif scenario=='dirfd-symlink-sentinels':
            for kind in ('final','intermediate','lock','marker'):
              deploy,previous,source,cdigest,proc=target(); outside=wd/('outside-'+kind); outside.mkdir(); sentinel=outside/'sentinel'; sentinel.write_bytes(b'unchanged')
              if kind=='final': (deploy/('a'*40)).symlink_to(outside, target_is_directory=True)
              elif kind=='intermediate': (deploy/'.ssm-runs').symlink_to(outside, target_is_directory=True)
              elif kind=='lock': (deploy/'.deploy.lock').symlink_to(sentinel)
              else:
                marker=run_path(cdigest,deploy=deploy)/'promotion-armed'; marker.parent.mkdir(parents=True); marker.symlink_to(sentinel)
              commands,local=render_deploy(deploy,cdigest); assert execute(commands,{**local,'FAKE_ARTIFACT':str(source)},deploy,proc).returncode!=0
              assert sentinel.read_bytes()==b'unchanged' and (deploy/'current.jar').resolve()==previous
              shutil.rmtree(deploy)
          elif scenario=='public-lock-symlink-sentinel':
            deploy,previous,source,cdigest,proc=target(); commands,local=render_deploy(deploy,cdigest); assert execute(commands,{**local,'FAKE_ARTIFACT':str(source)},deploy,proc).returncode==0
            outside=wd/'public-lock-outside'; outside.mkdir(); sentinel=outside/'sentinel'; sentinel.write_bytes(b'unchanged'); (deploy/'.deploy.lock').unlink(); (deploy/'.deploy.lock').symlink_to(sentinel)
            document,local=public_document(deploy,cdigest); assert execute(document,local,deploy,proc).returncode!=0
            assert sentinel.read_bytes()==b'unchanged' and (deploy/'current.jar').resolve()==deploy/('a'*40)/cdigest/'app.jar'
          elif scenario=='uid-gid-mode-rejected':
            deploy,previous,source,cdigest,proc=target(); commands,local=render_deploy(deploy,cdigest)
            for key,value in (('STAT_UID','1000'),('STAT_GID','1000'),('STAT_MODE','720'),('STAT_MODE','702')): assert execute(commands,{**local,'FAKE_ARTIFACT':str(source),key:value},deploy,proc).returncode!=0
          elif scenario=='workflow-upload-send-poll':
            artifact=wd/'artifact.jar'; artifact.write_bytes(b'candidate'); local={**env,'ARTIFACT_PATH':str(artifact),'ARTIFACT_DIGEST':digest(artifact.read_bytes()),'EXPECTED_DIGEST':digest(artifact.read_bytes())}
            assert run('Upload immutable artifact to S3 after DB gate',wd,local).returncode==0
            assert run('Upload immutable artifact to S3 after DB gate',wd,{**local,'PUT_ERROR':'conflict'}).returncode==0
            assert run('Upload immutable artifact to S3 after DB gate',wd,{**local,'PUT_ERROR':'conflict','HEAD_BAD_METADATA':'1'}).returncode!=0
            assert run('Upload immutable artifact to S3 after DB gate',wd,{**local,'PUT_ERROR':'conflict','HEAD_NO_VERSION':'1'}).returncode!=0
            assert run('Upload immutable artifact to S3 after DB gate',wd,{**local,'PUT_FAIL':'1'}).returncode!=0
            assert run('Render target DB gate command',wd,local).returncode==0
            assert run('Send and wait for pre-upload target DB gate',wd,local).returncode==0
            assert 's3api put-object --bucket bike-artifacts' in log.read_text() and 's3api head-object --bucket bike-artifacts' in log.read_text() and 'ssm send-command' in log.read_text() and 'ssm get-command-invocation' in log.read_text()
          elif scenario=='upload-only-exact-conflict-reconciles':
            artifact=wd/'artifact.jar'; artifact.write_bytes(b'candidate'); local={**env,'ARTIFACT_PATH':str(artifact),'ARTIFACT_DIGEST':digest(artifact.read_bytes()),'EXPECTED_DIGEST':digest(artifact.read_bytes())}
            for error in ('lookalike','other-operation','access','validation','timeout'):
              log.write_text(''); assert run('Upload immutable artifact to S3 after DB gate',wd,{**local,'PUT_ERROR':error}).returncode!=0
              assert 's3api:head-object' not in log.read_text()
          elif scenario=='manifest-negative':
            cases=[('missing',None),('malformed','{'),('bucket','bad'),('key','bad'),('versionId',''),('digest','f'*64),('disposition','bad')]
            for field,value in cases:
              local={**env}; data={'bucket':'bike-artifacts','key':'bike-back/releases/'+('a'*40)+'/'+local['EXPECTED_DIGEST']+'/app.jar','versionId':'version-1','disposition':'immutable-upload-or-verified-reuse','digest':local['EXPECTED_DIGEST']}; data[field]=value; (wd/'artifact-upload-manifest.json').write_text(json.dumps(data)); result=run('Preserve remote and runner evidence and final verdict',wd,local,config())
              if field=='missing': (wd/'artifact-upload-manifest.json').unlink()
              elif field=='malformed': (wd/'artifact-upload-manifest.json').write_text('{')
              result=run('Preserve remote and runner evidence and final verdict',wd,local,config())
              verdict=json.loads((wd/'deployment-evidence/final-verdict.json').read_text())
              assert result.returncode!=0 and verdict['finalVerdict']=='failure' and 'artifact-upload-manifest:validation' in verdict['evidenceValidationErrors']
          elif scenario=='jar-form-canonicalization':
            deploy,previous,source,cdigest,proc=target(); commands,local=render_deploy(deploy,cdigest)
            for jar in (str(deploy/('a'*40)/cdigest/'app.jar'),str(deploy/'current.jar')):
              proc.write_bytes(b'java\\0-jar\\0'+jar.encode()+b'\\0'); assert execute(commands,{**local,'FAKE_ARTIFACT':str(source)},deploy,proc).returncode==0
          elif scenario=='relative-current-jar-deploy-public':
            deploy,previous,source,cdigest,proc=target(); commands,local=render_deploy(deploy,cdigest); proc.write_bytes(b'java\\0-jar\\0current.jar\\0')
            assert execute(commands,{**local,'FAKE_ARTIFACT':str(source)},deploy,proc,deploy).returncode==0
            document,local=public_document(deploy,cdigest); assert execute(document,local,deploy,proc,deploy).returncode==0 and (deploy/'current.jar').resolve()==previous
            commands,local=render_deploy(deploy,cdigest,'102','1'); assert execute(commands,{**local,'FAKE_ARTIFACT':str(source)},deploy,proc,wd).returncode!=0 and (deploy/'current.jar').resolve()==previous
            proc.unlink(); assert execute(commands,{**local,'FAKE_ARTIFACT':str(source)},deploy,proc,deploy).returncode!=0 and (deploy/'current.jar').resolve()==previous
          elif scenario=='evidence-rendered-stdout':
            deploy,previous,source,cdigest,proc=target(); commands,local=render_deploy(deploy,cdigest); result=execute(commands,{**local,'FAKE_ARTIFACT':str(source)},deploy,proc)
            assert result.returncode==0 and len([line for line in result.stdout.splitlines() if line.strip()])==1
            invocation=json.dumps({'Status':'Success','StandardOutputContent':result.stdout})
            evidence_result=evidence_step({**local,'APP_DEPLOY_DIR':str(deploy),'FAKE_DEPLOY_INVOCATION':invocation},cfg_value=config(deploy))
            if evidence_result.returncode!=0: print((wd/'deployment-evidence/final-verdict.json').read_text(), invocation)
            assert evidence_result.returncode==0
            assert json.loads((wd/'deployment-evidence/final-verdict.json').read_text())['finalVerdict']=='success'
          elif scenario=='public-evidence-rendered-stdout':
            deploy,previous,source,cdigest,proc=target(); commands,local=render_deploy(deploy,cdigest); assert execute(commands,{**local,'FAKE_ARTIFACT':str(source)},deploy,proc).returncode==0
            document,local=public_document(deploy,cdigest); result=execute(document,local,deploy,proc)
            assert result.returncode==0 and len([line for line in result.stdout.splitlines() if line.strip()])==1
            (wd/'rollback-command-id.txt').write_text('roll-1')
            invocation=json.dumps({'Status':'Success','StandardOutputContent':result.stdout})
            deploy_invocation=json.dumps({'Status':'Success','StandardOutputContent':(run_path(cdigest)/'deploy-evidence.json').read_text()})
            assert evidence_step({**local,'APP_DEPLOY_DIR':str(deploy),'FAKE_DEPLOY_INVOCATION':deploy_invocation,'FAKE_ROLL_INVOCATION':invocation},cfg_value=config(deploy)).returncode==0
          elif scenario=='rollback-failure':
            deploy,previous,source,cdigest,proc=target(); commands,local=render_deploy(deploy,cdigest); assert execute(commands,{**local,'FAKE_ARTIFACT':str(source)},deploy,proc).returncode==0
            document,local=public_document(deploy,cdigest); result=execute(document,{**local,'CURL_MODE':'rollback-fail'},deploy,proc); assert result.returncode!=0
            status=json.loads((run_path(cdigest)/'public-rollback-status.json').read_text()); assert status['rollbackStatus']!=0
            (wd/'rollback-command-id.txt').write_text('roll-1')
            failed=json.dumps({'Status':'Failed','StandardOutputContent':result.stdout})
            assert evidence_step({**local,'FAKE_ROLL_INVOCATION':failed}).returncode!=0
            assert json.loads((wd/'deployment-evidence/public-rollback.json').read_text())['Status']=='Failed'
            assert (wd/'deployment-evidence/final-verdict.json').exists()
          elif scenario=='stale-marker-rejected':
            deploy,previous,source,cdigest,proc=target(); commands,local=render_deploy(deploy,cdigest); assert execute(commands,{**local,'FAKE_ARTIFACT':str(source)},deploy,proc).returncode==0
            marker=run_path(cdigest)/'promotion-armed'; marker.write_text(marker.read_text().replace('candidate='+str(deploy/('a'*40)/cdigest/'app.jar'),'candidate='+str(deploy/('a'*40)/('0'*64)/'app.jar')))
            document,local=public_document(deploy,cdigest); assert execute(document,local,deploy,proc).returncode!=0
            assert not (run_path(cdigest)/'public-rollback-status.json').exists()
          elif scenario=='candidate-replaced-rejected':
            deploy,previous,source,cdigest,proc=target(); commands,local=render_deploy(deploy,cdigest); assert execute(commands,{**local,'FAKE_ARTIFACT':str(source)},deploy,proc).returncode==0
            replacement=b'candidate-b'; replacement_digest=digest(replacement); replacement_path=deploy/('c'*40)/replacement_digest/'app.jar'; replacement_path.parent.mkdir(parents=True); replacement_path.write_bytes(replacement)
            (deploy/'current.jar').unlink(); (deploy/'current.jar').symlink_to(replacement_path)
            document,local=public_document(deploy,cdigest); assert execute(document,local,deploy,proc).returncode!=0
            assert (deploy/'current.jar').resolve()==replacement_path
            assert not (run_path(cdigest)/'public-rollback-status.json').exists()
          elif scenario=='cross-run-rejected':
            deploy,previous,source,cdigest,proc=target(); commands,local=render_deploy(deploy,cdigest); assert execute(commands,{**local,'FAKE_ARTIFACT':str(source)},deploy,proc).returncode==0
            document,local=public_document(deploy,cdigest,'102','1'); assert execute(document,local,deploy,proc).returncode!=0
            assert (deploy/'current.jar').resolve()==deploy/('a'*40)/cdigest/'app.jar'
            assert not (run_path(cdigest,'102')/'public-rollback-status.json').exists()
            assert 'flock -n 9' in log.read_text()
          elif scenario=='lock-contention-rejected':
            deploy,previous,source,cdigest,proc=target(); commands,local=render_deploy(deploy,cdigest)
            assert execute(commands,{**local,'FAKE_ARTIFACT':str(source),'FLOCK_FAIL':'1'},deploy,proc).returncode!=0
            assert (deploy/'current.jar').resolve()==previous and not (run_path(cdigest)/'promotion-armed').exists()
            assert execute(commands,{**local,'FAKE_ARTIFACT':str(source)},deploy,proc).returncode==0
            document,local=public_document(deploy,cdigest); marker=(run_path(cdigest)/'promotion-armed').read_text()
            assert execute(document,{**local,'FLOCK_FAIL':'1'},deploy,proc).returncode!=0
            assert (deploy/'current.jar').resolve()==deploy/('a'*40)/cdigest/'app.jar'
            assert (run_path(cdigest)/'promotion-armed').read_text()==marker and not (run_path(cdigest)/'public-rollback-status.json').exists()
          elif scenario=='always-evidence':
            (wd/'rollback-command-id.txt').write_text('roll-1')
            evidence_result=evidence_step(env)
            if evidence_result.returncode!=0: print((wd/'deployment-evidence/final-verdict.json').read_text())
            assert evidence_result.returncode==0
            public=json.loads((wd/'deployment-evidence/public-rollback.json').read_text())
            assert public['Status']=='Success' and 'candidate' in public['StandardOutputContent']
            verdict=json.loads((wd/'deployment-evidence/final-verdict.json').read_text()); assert verdict['evidenceValid'] is True
          elif scenario=='evidence-absent-ids':
            assert evidence_step(env,('','')).returncode!=0
            for name in ('preupload-db-gate.json','deploy-state-machine.json','public-rollback.json'):
              assert json.loads((wd/'deployment-evidence'/name).read_text())['state']=='not-run'
            verdict=json.loads((wd/'deployment-evidence/final-verdict.json').read_text()); assert verdict['finalVerdict']=='failure' and verdict['evidenceValid'] is False and 'preupload-db-gate:validation' in verdict['evidenceValidationErrors'] and 'deploy-state-machine:validation' in verdict['evidenceValidationErrors']
          elif scenario=='evidence-incoming-failure':
            assert evidence_step({**env,'JOB_STATUS':'failure'},('','')).returncode!=0
            verdict=json.loads((wd/'deployment-evidence/final-verdict.json').read_text()); assert verdict['incomingJobStatus']=='failure' and verdict['finalVerdict']=='failure'
          elif scenario=='evidence-retrieval-error':
            assert evidence_step(env,('unknown-7','')).returncode!=0
            retrieval=json.loads((wd/'deployment-evidence/preupload-db-gate.json').read_text())
            assert retrieval=={'phase':'preupload-db-gate','state':'retrieval-error','commandId':'unknown-7','exitCode':7}
            verdict=json.loads((wd/'deployment-evidence/final-verdict.json').read_text())
            assert verdict['incomingJobStatus']=='success' and verdict['finalVerdict']=='failure' and verdict['evidenceValid'] is False and 'preupload-db-gate:retrieval' in verdict['evidenceValidationErrors']
          elif scenario=='evidence-malformed':
            malformed=json.dumps({'Status':'Success','StandardOutputContent':'{"sha":"wrong"}'+chr(10)+'{"sha":"duplicate"}'})
            assert evidence_step({**env,'FAKE_DEPLOY_INVOCATION':malformed}).returncode!=0
            assert json.loads((wd/'deployment-evidence/final-verdict.json').read_text())['finalVerdict']=='failure'
          elif scenario=='evidence-identity-mismatch':
            for field,value in (('sha','wrong'),('digest','f'*64),('runId','999'),('runAttempt','9'),('previous','/bad/path'),('failedRows',1)):
              record=json.loads(deploy_evidence); record['StandardOutputContent']=json.dumps({**json.loads(record['StandardOutputContent']),field:value})
              assert evidence_step({**env,'FAKE_DEPLOY_INVOCATION':json.dumps(record)}).returncode!=0
              assert json.loads((wd/'deployment-evidence/final-verdict.json').read_text())['finalVerdict']=='failure'
            for field,value in (('candidate','/bad/path'),('rollbackStatus',1)):
              (wd/'rollback-command-id.txt').write_text('roll-1'); record=json.loads(roll_evidence); record['StandardOutputContent']=json.dumps({**json.loads(record['StandardOutputContent']),field:value})
              assert evidence_step({**env,'FAKE_ROLL_INVOCATION':json.dumps(record)}).returncode!=0
          elif scenario=='aws-negative-argv':
            assert subprocess.run(['aws','ssm','get-parameter','--name','/bike/db'],cwd=wd,env=env).returncode!=0
            assert subprocess.run(['aws','ssm','get-command-invocation','--command-id','db-1','--instance-id','i-aaaaaaaa','--query','Wrong','--output','text'],cwd=wd,env=env).returncode!=0
            assert subprocess.run(['aws','s3api','get-object','--bucket','bike-artifacts'],cwd=wd,env=env).returncode!=0
            valid=['aws','ssm','send-command','--instance-ids','i-aaaaaaaa','--document-name','AWS-RunShellScript','--comment','ok','--parameters','file://ssm-deploy.json','--query','Command.CommandId','--output','text']
            assert subprocess.run(valid,cwd=wd,env=env).returncode==0
            for bad in (valid[:-1],valid+['extra'],valid[:3]+['wrong']+valid[4:],valid[:9]+['file://wrong.json']+valid[10:],valid[:11]+['Wrong']+valid[12:]): assert subprocess.run(bad,cwd=wd,env=env).returncode!=0
        """;
}
