package com.greedy.section02.superkeyword;

import java.util.Date;

public class ComputerDTO extends ProductDTO{

	/* Computer가 가지는 추가적인 속성 */
	private String cpu;										// cpu
	private int hdd;										// 하드
	private int ram;										// 렘
	private String operationSystem;							// 운영체제
	
	
	public ComputerDTO() {
		super();
	}

	public ComputerDTO(String cpu, int hdd, int ram, String operationSystem) {
		super();
		this.cpu = cpu;
		this.hdd = hdd;
		this.ram = ram;
		this.operationSystem = operationSystem;
	}

	
	public ComputerDTO(String code, String brand, String name, int price, Date manufacturingDate, String cpu, int hdd,
			int ram, String operationSystem) {
		super(code, brand, name, price, manufacturingDate);
		this.cpu = cpu;
		this.hdd = hdd;
		this.ram = ram;
		this.operationSystem = operationSystem;
	}

	public String getCpu() {
		return cpu;
	}


	public void setCpu(String cpu) {
		this.cpu = cpu;
	}


	public int getHdd() {
		return hdd;
	}


	public void setHdd(int hdd) {
		this.hdd = hdd;
	}


	public int getRam() {
		return ram;
	}


	public void setRam(int ram) {
		this.ram = ram;
	}


	public String getOperationSystem() {
		return operationSystem;
	}


	public void setOperationSystem(String operationSystem) {
		this.operationSystem = operationSystem;
	}


	@Override
	public String toString() {
		return super.toString() + "ComputerDTO [cpu=" + cpu + ", hdd=" + hdd + ", ram=" + ram + ", operationSystem=" + operationSystem
				+ "]";
	}
	
	
	
}
