package com.greedy.section04.array_sort;

public class Application4 {

    public static void main(String[] args) {

        /* 버블 정렬(bubble sort) */
        /* 인접한 두 개의 원소를 검사하여 정렬하는 방법
         * 구현이 쉽다는 장점이 있으며, 이미 정렬된 데이터를 정렬할 때 가장 빠르다.
         * 하지만 다른 정렬에 비해 정렬 속도가 느리며, 역순으로 정렬할 때 가장 느린 속도를 가진다.
         * */
        
        /* 초기 배열 선언 및 초기화 */
        int[] iarr = {2,5,4,6,1,3};
        
        int temp;
        
        for(int i = iarr.length - 1; i >= 0; i--){
            for(int j = 0; j < i; j++) {
                
                if(iarr[j] > iarr[j + 1]) {
                    
                    temp = iarr[j];
                    iarr[j] = iarr[j + 1];
                    iarr[j+1] = temp;
                }
                
            }
        }
        
        /* 값 출력용 반복문 */
        for(int i = 0; i < iarr.length; i++){
            System.out.print(iarr[i] + " ");
        }

    }

}