<#ftl output_format='HTML'>
<!DOCTYPE html>
<html>
<head>
<title>${getText('launch')}${getText('job')} - ${getText(job.name)}</title>
</head>
<body>
<@s.form action="${actionBaseUrl}/launch" method="post" class="form-horizontal ajax">
	<@s.hidden name="id"/>
	<#if params??>
	<#if params?size gt 0>
	<#list params as param>
	<#assign index = param?index/>
	<div id="params_${index}">
	<#if param.type??>
	<#assign type = 'text'/>
	<#assign dynamicAttributes = {}/>
	<#if param.type.name()=='LONG'><#assign type = 'number'/><#assign dynamicAttributes = {'step':'1'}/><#elseif param.type.name()=='DOUBLE'><#assign type = 'number'/><#assign dynamicAttributes = {'step':'0.01'}/></#if>
	<@s.textfield type=type label=getText(param.key) name="params[${index}].value" class="${param.required?then('required','')} ${param.type?lower_case}" dynamicAttributes=dynamicAttributes/>
	<@s.hidden name="params[${index}].type"/>
	<#else>
	<div class="row-fluid">
	<div class="span4">
	<@s.select name="params[${index}].type" class="input-small conjunct" data\-replacement="params_${index}" data\-global="false" list="paramTypes"/>
	</div>
	<div class="span5">
	<@s.textfield label=getText(param.key) name="params[${index}].value" class="${param.required?then('required','')}"/>
	</div>
	</div>
	</#if>
	</div>
	</#list>
	<#else>
	<@s.textarea name="jobParameters" class="required input-xxlarge" placeholder="workdate(date)=2012-12-12\ncount(long)=12\namount(double)=12.12\nname=test"/>
	</#if>
	</#if>
	<@s.submit label=getText('launch') class="btn-primary"/>
</@s.form>
</body>
</html>


