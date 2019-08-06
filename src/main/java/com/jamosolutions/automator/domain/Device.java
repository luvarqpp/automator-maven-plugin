package com.jamosolutions.automator.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;

public class Device {
	private String name;
	private String udid;
	private List<TestCase> testCases;

	@XmlAttribute
	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	@XmlAttribute
	public String getUdid() {
		return udid;
	}

	public void setUdid(String udid) {
		this.udid = udid;
	}

	@XmlElement(name = "testcase")
	public List<TestCase> getTestCases() {
		if(null == testCases) {
			testCases = new ArrayList<>(16);
		}
		return testCases;
	}

	public void setTestCases(List<TestCase> testCases) {
		this.testCases = testCases;
	}

	@Override
	public String toString() {
		return "Device(" + this.name + ";" + this.udid + ")";
	}
}